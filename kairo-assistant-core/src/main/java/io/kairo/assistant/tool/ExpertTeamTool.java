/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.assistant.tool;

import io.kairo.api.agent.Agent;
import io.kairo.api.team.Team;
import io.kairo.api.team.TeamConfig;
import io.kairo.api.team.TeamExecutionRequest;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.expertteam.ExpertTeamComposer;
import io.kairo.expertteam.ExpertTeamCoordinator;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Dispatches a complex goal to an in-process expert team. Use when the user asks for something
 * that splits cleanly into parallel sub-tasks ("plan + book my Tokyo trip", "research and
 * summarize 5 papers", "draft 3 variants of an email").
 *
 * <p>Each invocation builds a fresh team via {@link ExpertTeamComposer}, runs the upstream
 * {@link ExpertTeamCoordinator}'s plan / generate / evaluate cycle, and returns the team's final
 * output. Workers are spawned through {@link #setAgentSupplier(Supplier)} which the assistant
 * runtime wires to its own {@code AssistantAgentFactory.create(childConfig)}.
 *
 * <p>Why a static supplier (vs. constructor injection) — {@link Tool}-annotated classes are
 * instantiated by reflection in the tool registry; a static hook lets the runtime install the
 * factory at startup without requiring everyone to construct the tool manually.
 */
@Tool(
        name = "expert_team",
        description =
                "Run an expert team on a complex goal. Use for goals that split into parallel "
                        + "sub-tasks (research + draft + review, plan + book + remind, etc.). "
                        + "Returns the team's consolidated output.",
        category = ToolCategory.AGENT_AND_TASK,
        timeoutSeconds = 600,
        sideEffect = ToolSideEffect.WRITE)
public class ExpertTeamTool implements SyncTool {

    private static final Logger log = LoggerFactory.getLogger(ExpertTeamTool.class);
    private static final int DEFAULT_AGENT_COUNT = 3;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private static volatile Supplier<Agent> agentSupplier;

    /**
     * Install the assistant-side agent factory. Called once during runtime bootstrap so this
     * tool can build worker agents without compile-time dependency on AssistantAgentFactory.
     */
    public static void setAgentSupplier(Supplier<Agent> supplier) {
        agentSupplier = supplier;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put(
                "goal",
                new JsonSchema(
                        "string",
                        null,
                        null,
                        "The goal for the expert team. State it as a single complete request."));
        props.put(
                "agentCount",
                new JsonSchema(
                        "integer",
                        null,
                        null,
                        "Number of worker agents (default 3). Increase for goals with more"
                                + " parallel sub-tasks."));
        return new JsonSchema("object", props, List.of("goal"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doInvoke(args, ctx));
    }

    private ToolResult doInvoke(Map<String, Object> args, ToolContext ctx) {
        String toolUseId = "expert_team";
        String goal = args.get("goal") instanceof String s ? s : null;
        if (goal == null || goal.isBlank()) {
            return ToolResult.error(toolUseId, "goal must not be blank");
        }
        Supplier<Agent> supplier = agentSupplier;
        if (supplier == null) {
            return ToolResult.error(
                    toolUseId,
                    "ExpertTeamTool not initialized: call ExpertTeamTool.setAgentSupplier"
                            + " during runtime bootstrap.");
        }
        int agentCount = parseAgentCount(args);

        try {
            var composition = ExpertTeamComposer.create(agentCount, supplier);
            ExpertTeamCoordinator coordinator = composition.coordinator();
            Team team =
                    new Team("expert-team-" + UUID.randomUUID(), composition.agents(),
                            composition.messageBus());
            TeamExecutionRequest req =
                    new TeamExecutionRequest(
                            UUID.randomUUID().toString(),
                            goal,
                            Map.of(),
                            TeamConfig.defaults());
            var result = coordinator.execute(req, team).block(DEFAULT_TIMEOUT);
            if (result == null) {
                return ToolResult.error(toolUseId, "team execution returned no result");
            }
            String body =
                    result.finalOutput()
                            .orElse(
                                    "(team status="
                                            + result.status()
                                            + ", "
                                            + result.stepOutcomes().size()
                                            + " steps, no final output)");
            return ToolResult.success(toolUseId, body);
        } catch (Exception e) {
            log.warn("ExpertTeamTool execution failed: {}", e.getMessage(), e);
            return ToolResult.error(toolUseId, "expert team failed: " + e.getMessage());
        }
    }

    private static int parseAgentCount(Map<String, Object> args) {
        Object v = args.get("agentCount");
        if (v instanceof Number n) {
            int parsed = n.intValue();
            if (parsed >= 1 && parsed <= 10) return parsed;
        }
        return DEFAULT_AGENT_COUNT;
    }
}
