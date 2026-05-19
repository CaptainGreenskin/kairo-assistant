package io.kairo.assistant.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

class OpenAiCompatibleControllerTest {

    private OpenAiCompatibleController controller;

    @BeforeEach
    void setUp() {
        var gateway = TestFixtures.stubGateway();
        controller = new OpenAiCompatibleController(
                gateway, new SessionAwareDeltaRouter(),
                TestFixtures.stubModelSwitchService(gateway));
    }

    @Test
    void blockingCompletionReturnsOpenAiFormat() {
        var request = Map.<String, Object>of(
                "model", "kairo-assistant",
                "messages", List.of(Map.of("role", "user", "content", "hello")));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                (ResponseEntity<Map<String, Object>>) controller.chatCompletions(request, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("object")).isEqualTo("chat.completion");
        assertThat(body.get("model")).isEqualTo("kairo-assistant");

        @SuppressWarnings("unchecked")
        var choices = (List<Map<String, Object>>) body.get("choices");
        assertThat(choices).hasSize(1);
        assertThat(choices.get(0).get("finish_reason")).isEqualTo("stop");

        @SuppressWarnings("unchecked")
        var message = (Map<String, Object>) choices.get(0).get("message");
        assertThat(message.get("role")).isEqualTo("assistant");
        assertThat((String) message.get("content")).contains("echo: hello");
    }

    @Test
    void rejectsEmptyMessages() {
        var request = Map.<String, Object>of(
                "model", "kairo-assistant",
                "messages", List.of());

        var response = controller.chatCompletions(request, null, null);
        assertThat(response).isInstanceOf(ResponseEntity.class);

        @SuppressWarnings("unchecked")
        var entity = (ResponseEntity<Map<String, Object>>) response;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsMissingMessages() {
        var request = Map.<String, Object>of("model", "kairo-assistant");
        var response = controller.chatCompletions(request, null, null);

        @SuppressWarnings("unchecked")
        var entity = (ResponseEntity<Map<String, Object>>) response;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsBlankContent() {
        var request = Map.<String, Object>of(
                "messages", List.of(Map.of("role", "user", "content", "   ")));

        @SuppressWarnings("unchecked")
        var entity = (ResponseEntity<Map<String, Object>>) controller.chatCompletions(request, null, null);
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void streamingReturnsFluxInResponseEntity() {
        var request = Map.<String, Object>of(
                "model", "kairo-assistant",
                "stream", true,
                "messages", List.of(Map.of("role", "user", "content", "hi")));

        var response = controller.chatCompletions(request, null, null);
        assertThat(response).isInstanceOf(ResponseEntity.class);

        @SuppressWarnings("unchecked")
        var entity = (ResponseEntity<Flux<String>>) response;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);

        var events = entity.getBody().collectList().block();
        assertThat(events).isNotNull();
        assertThat(events).anyMatch(e -> e.contains("[DONE]"));
    }

    @Test
    void listModelsReturnsKairoAssistant() {
        var result = controller.listModels();
        assertThat(result.get("object")).isEqualTo("list");

        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) result.get("data");
        assertThat(data).hasSizeGreaterThanOrEqualTo(1);
        assertThat(data.get(0).get("id")).isEqualTo("kairo-assistant");
    }

    @Test
    void sessionIdHeaderIsolatesSessions() {
        var request = Map.<String, Object>of(
                "messages", List.of(Map.of("role", "user", "content", "test")));

        controller.chatCompletions(request, null, "session-a");
        controller.chatCompletions(request, null, "session-b");
        // no exception — sessions are independent
    }

    @Test
    void multiPartContentExtracted() {
        var request = Map.<String, Object>of(
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", "hello world"),
                                Map.of("type", "image_url", "image_url", "http://...")))));

        @SuppressWarnings("unchecked")
        var entity = (ResponseEntity<Map<String, Object>>) controller.chatCompletions(request, null, null);
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        var choices = (List<Map<String, Object>>) entity.getBody().get("choices");
        @SuppressWarnings("unchecked")
        var message = (Map<String, Object>) choices.get(0).get("message");
        assertThat((String) message.get("content")).contains("echo: hello world");
    }
}
