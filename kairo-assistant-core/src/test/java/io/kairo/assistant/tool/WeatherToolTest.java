package io.kairo.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolResult;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeatherToolTest {

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
        WeatherTool tool = new WeatherTool();
        ToolResult result = tool.execute(Map.of(), null).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("location");
    }

    @Test
    void executeRejectsBlankLocation() {
        WeatherTool tool = new WeatherTool();
        ToolResult result = tool.execute(Map.of("location", "  "), null).block();
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("location");
    }

    @Test
    void inputSchemaRequiresLocation() {
        WeatherTool tool = new WeatherTool();
        var schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.required()).contains("location");
    }

    @Test
    void toolAnnotationPresent() {
        var annotation = WeatherTool.class.getAnnotation(io.kairo.api.tool.Tool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("weather");
    }
}
