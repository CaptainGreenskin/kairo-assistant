package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WeatherToolTest {

    private static HttpServer server;
    private static WeatherTool toolWithServer;
    private final ToolContext ctx = new ToolContext("a", "s", null);

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] resp;
            if (query != null && query.contains("UnknownCity")) {
                resp = """
                        {"results":[]}
                        """.getBytes();
            } else {
                resp = """
                        {"results":[{"latitude":39.9,"longitude":116.4,"name":"Beijing"}]}
                        """.getBytes();
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.createContext("/v1/forecast", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] resp;
            if (query != null && query.contains("daily=")) {
                resp = """
                        {"timezone":"Asia/Shanghai","daily":{
                          "time":["2026-05-18","2026-05-19","2026-05-20"],
                          "weather_code":[0,3,61],
                          "temperature_2m_max":[30.0,28.0,25.0],
                          "temperature_2m_min":[20.0,18.0,15.0],
                          "precipitation_sum":[0.0,0.0,5.2],
                          "wind_speed_10m_max":[15.0,20.0,30.0]
                        }}
                        """.getBytes();
            } else {
                resp = """
                        {"timezone":"Asia/Shanghai","current":{
                          "temperature_2m":25.5,"relative_humidity_2m":60,
                          "wind_speed_10m":12.3,"weather_code":2
                        }}
                        """.getBytes();
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        // WeatherTool uses hardcoded URLs, so we can't inject the server URL directly.
        // Tests that need the real server behavior use the default tool.
        // For unit tests, we test parsing and validation.
        toolWithServer = new WeatherTool();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void weatherCodeToTextMapsCommonCodes() {
        assertThat(WeatherTool.weatherCodeToText(0)).isEqualTo("Clear sky");
        assertThat(WeatherTool.weatherCodeToText(3)).isEqualTo("Overcast");
        assertThat(WeatherTool.weatherCodeToText(61)).isEqualTo("Rain");
        assertThat(WeatherTool.weatherCodeToText(95)).isEqualTo("Thunderstorm");
        assertThat(WeatherTool.weatherCodeToText(999)).startsWith("Unknown");
    }

    @Test
    void weatherCodeToTextCoversAllBranches() {
        assertThat(WeatherTool.weatherCodeToText(1)).isEqualTo("Mainly clear");
        assertThat(WeatherTool.weatherCodeToText(2)).isEqualTo("Partly cloudy");
        assertThat(WeatherTool.weatherCodeToText(45)).isEqualTo("Foggy");
        assertThat(WeatherTool.weatherCodeToText(48)).isEqualTo("Foggy");
        assertThat(WeatherTool.weatherCodeToText(51)).isEqualTo("Drizzle");
        assertThat(WeatherTool.weatherCodeToText(66)).isEqualTo("Freezing rain");
        assertThat(WeatherTool.weatherCodeToText(71)).isEqualTo("Snowfall");
        assertThat(WeatherTool.weatherCodeToText(77)).isEqualTo("Snow grains");
        assertThat(WeatherTool.weatherCodeToText(80)).isEqualTo("Rain showers");
        assertThat(WeatherTool.weatherCodeToText(85)).isEqualTo("Snow showers");
        assertThat(WeatherTool.weatherCodeToText(96)).isEqualTo("Thunderstorm with hail");
    }

    @Test
    void executeRejectsMissingLocation() {
        ToolResult result = toolWithServer.execute(Map.of(), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("location");
    }

    @Test
    void executeRejectsBlankLocation() {
        ToolResult result = toolWithServer.execute(Map.of("location", "  "), ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("location");
    }

    @Test
    void inputSchemaRequiresLocation() {
        var schema = toolWithServer.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.required()).contains("location");
        assertThat(schema.properties()).containsKey("action");
    }

    @Test
    void toolAnnotationPresent() {
        var annotation = WeatherTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("weather");
    }

    @Test
    void defaultActionIsCurrent() {
        var schema = toolWithServer.inputSchema();
        assertThat(schema.properties().get("action").description()).contains("current");
        assertThat(schema.properties().get("action").description()).contains("forecast");
    }
}
