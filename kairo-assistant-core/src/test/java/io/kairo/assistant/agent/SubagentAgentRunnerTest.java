/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.model.ModelConfig;
import io.kairo.api.model.ModelProvider;
import io.kairo.api.model.ModelResponse;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Smoke test for {@link SubagentAgentRunner} — spawns an ephemeral agent against a fake {@link
 * ModelProvider} and confirms the runner returns the model's reply text. The real provider /
 * tool dispatch happens at the {@code AgentBuilder} layer (covered by upstream tests); here we
 * just prove the runner correctly hands off the prompt and propagates the reply.
 */
class SubagentAgentRunnerTest {

    @Test
    void runReturnsModelReplyText() {
        AtomicReference<String> seenPrompt = new AtomicReference<>();

        ModelProvider fake =
                new ModelProvider() {
                    @Override
                    public String name() {
                        return "fake";
                    }

                    @Override
                    public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
                        // Capture the user message text so we can assert the prompt was forwarded.
                        for (Msg m : messages) {
                            if (m.role() == io.kairo.api.message.MsgRole.USER) {
                                seenPrompt.set(m.text());
                                break;
                            }
                        }
                        return Mono.just(
                                new ModelResponse(
                                        "resp-1",
                                        List.of(new Content.TextContent("hook decision: ok")),
                                        new ModelResponse.Usage(10, 5, 15, 0),
                                        ModelResponse.StopReason.END_TURN,
                                        config == null ? "fake" : config.model()));
                    }

                    @Override
                    public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
                        return call(messages, config).flux();
                    }
                };

        var toolRegistry = new DefaultToolRegistry();
        var toolExecutor = new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());

        var runner =
                new SubagentAgentRunner(
                        fake,
                        "test-model",
                        toolRegistry,
                        toolExecutor,
                        "you are a tiny hook agent");

        String result = runner.run("audit ls -al", null).block(Duration.ofSeconds(10));
        assertThat(result).isNotNull().contains("hook decision: ok");
        assertThat(seenPrompt.get()).isEqualTo("audit ls -al");
    }

    @Test
    void blankPromptReturnsEmptyImmediately() {
        // No model call should happen — short-circuit on empty input.
        ModelProvider exploding =
                new ModelProvider() {
                    @Override
                    public String name() {
                        return "exploding";
                    }

                    @Override
                    public Mono<ModelResponse> call(List<Msg> messages, ModelConfig config) {
                        return Mono.error(new AssertionError("must not be called for blank prompt"));
                    }

                    @Override
                    public Flux<ModelResponse> stream(List<Msg> messages, ModelConfig config) {
                        return Flux.error(new AssertionError("must not be called for blank prompt"));
                    }
                };
        var toolRegistry = new DefaultToolRegistry();
        var toolExecutor = new DefaultToolExecutor(toolRegistry, new DefaultPermissionGuard());

        var runner =
                new SubagentAgentRunner(exploding, "x", toolRegistry, toolExecutor, "sys");
        assertThat(runner.run("  ", null).block(Duration.ofSeconds(1))).isEqualTo("");
        assertThat(runner.run(null, null).block(Duration.ofSeconds(1))).isEqualTo("");
    }
}
