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
                "Get current weather for a location using the Open-Meteo API (free, no API key).",
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
        props.put(
                "location",
                new JsonSchema(
                        "string",
                        null,
                        null,
                        "City name or location (e.g., 'Beijing', 'San Francisco')."));
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

        try {
            double[] coords = geocode(location);
            if (coords == null) {
                return ToolResult.error("weather", "Location not found: " + location);
            }

            String weatherJson = fetchWeather(coords[0], coords[1]);
            return parseWeather(weatherJson, location);
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
