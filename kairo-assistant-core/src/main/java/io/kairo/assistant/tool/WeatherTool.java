package io.kairo.assistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "weather",
        description =
                "Get weather for a location using the Open-Meteo API (free, no API key). "
                        + "Actions: current (default), forecast (7-day daily outlook).",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.READ_ONLY)
public class WeatherTool implements SyncTool {

    private static final String GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public WeatherTool() {
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    WeatherTool(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("location", new JsonSchema("string", null, null,
                "City name or location (e.g., 'Beijing', 'San Francisco')."));
        props.put("action", new JsonSchema("string", null, null,
                "'current' (default) or 'forecast' for 7-day daily outlook."));
        return new JsonSchema("object", props, List.of("location"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args));
    }

    private ToolResult doExecute(Map<String, Object> args) {
        String location = (String) args.get("location");
        if (location == null || location.isBlank()) {
            return ToolResult.error("weather", "Parameter 'location' is required");
        }

        String action = args.get("action") instanceof String a ? a : "current";

        try {
            double[] coords = geocode(location);
            if (coords == null) {
                return ToolResult.error("weather", "Location not found: " + location);
            }

            return switch (action.toLowerCase()) {
                case "forecast" -> {
                    String json = fetchForecast(coords[0], coords[1]);
                    yield parseForecast(json, location);
                }
                default -> {
                    String json = fetchWeather(coords[0], coords[1]);
                    yield parseWeather(json, location);
                }
            };
        } catch (Exception e) {
            return ToolResult.error("weather", "Weather fetch failed: " + e.getMessage());
        }
    }

    private double[] geocode(String location) throws Exception {
        String url =
                GEOCODE_URL
                        + "?name="
                        + URLEncoder.encode(location, StandardCharsets.UTF_8)
                        + "&count=1";
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return null;
        }
        JsonNode first = results.get(0);
        return new double[] {first.get("latitude").asDouble(), first.get("longitude").asDouble()};
    }

    private String fetchWeather(double lat, double lon) throws Exception {
        String url =
                WEATHER_URL
                        + "?latitude="
                        + lat
                        + "&longitude="
                        + lon
                        + "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code"
                        + "&timezone=auto";
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private String fetchForecast(double lat, double lon) throws Exception {
        String url = WEATHER_URL
                + "?latitude=" + lat
                + "&longitude=" + lon
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                + "precipitation_sum,wind_speed_10m_max"
                + "&timezone=auto";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private ToolResult parseForecast(String json, String location) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode daily = root.get("daily");
        if (daily == null) {
            return ToolResult.error("weather", "No forecast data available");
        }

        JsonNode times = daily.get("time");
        JsonNode maxTemps = daily.get("temperature_2m_max");
        JsonNode minTemps = daily.get("temperature_2m_min");
        JsonNode codes = daily.get("weather_code");
        JsonNode precip = daily.get("precipitation_sum");
        JsonNode wind = daily.get("wind_speed_10m_max");
        String tz = root.path("timezone").asText("UTC");

        StringBuilder sb = new StringBuilder();
        sb.append("7-day forecast for ").append(location).append(" (").append(tz).append("):\n\n");

        int days = times != null ? times.size() : 0;
        for (int i = 0; i < days; i++) {
            String date = times.get(i).asText();
            double hi = maxTemps != null ? maxTemps.get(i).asDouble() : 0;
            double lo = minTemps != null ? minTemps.get(i).asDouble() : 0;
            int wCode = codes != null ? codes.get(i).asInt() : -1;
            double rain = precip != null ? precip.get(i).asDouble() : 0;
            double maxWind = wind != null ? wind.get(i).asDouble() : 0;

            sb.append(String.format("  %s: %s, %.0f/%.0f°C",
                    date, weatherCodeToText(wCode), lo, hi));
            if (rain > 0) sb.append(String.format(", %.1fmm rain", rain));
            if (maxWind > 0) sb.append(String.format(", wind %.0f km/h", maxWind));
            sb.append('\n');
        }

        return ToolResult.success("weather", sb.toString().trim());
    }

    private ToolResult parseWeather(String json, String location) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode current = root.get("current");
        if (current == null) {
            return ToolResult.error("weather", "No current weather data available");
        }

        double temp = current.path("temperature_2m").asDouble();
        int humidity = current.path("relative_humidity_2m").asInt();
        double wind = current.path("wind_speed_10m").asDouble();
        int code = current.path("weather_code").asInt();
        String condition = weatherCodeToText(code);
        String tz = root.path("timezone").asText("UTC");

        String result =
                String.format(
                        "Weather in %s (%s):\n"
                                + "  Condition: %s\n"
                                + "  Temperature: %.1f°C\n"
                                + "  Humidity: %d%%\n"
                                + "  Wind: %.1f km/h",
                        location, tz, condition, temp, humidity, wind);

        return ToolResult.success("weather", result);
    }

    static String weatherCodeToText(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1 -> "Mainly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Foggy";
            case 51, 53, 55 -> "Drizzle";
            case 61, 63, 65 -> "Rain";
            case 66, 67 -> "Freezing rain";
            case 71, 73, 75 -> "Snowfall";
            case 77 -> "Snow grains";
            case 80, 81, 82 -> "Rain showers";
            case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Unknown (code " + code + ")";
        };
    }
}
