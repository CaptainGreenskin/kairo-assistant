/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.assistant.server;

import io.kairo.api.evolution.EvolvedSkill;
import io.kairo.api.evolution.SkillTelemetry;
import io.kairo.evolution.curator.ConsolidationReport;
import io.kairo.evolution.curator.CuratorAction;
import io.kairo.evolution.curator.LifecycleTransitionResult;
import io.kairo.evolution.curator.UmbrellaConsolidationPlanner;
import io.kairo.spring.evolution.EvolutionController;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter that exposes the kairo-spring-boot-starter-evolution {@link EvolutionController}
 * under {@code /api/evolution}. Only mounts when the curator beans are available — i.e. when {@code
 * kairo.evolution.curator.enabled=true}.
 */
@Configuration
@ConditionalOnBean(EvolutionController.class)
class EvolutionRestControllerConfig {

    @RestController
    @RequestMapping("/api/evolution")
    static class Endpoints {

        private final EvolutionController controller;
        private final DashboardEventPublisher dashboard;

        Endpoints(EvolutionController controller, DashboardEventPublisher dashboard) {
            this.controller = controller;
            this.dashboard = dashboard;
        }

        @GetMapping("/skills")
        public Map<String, Object> listSkills() {
            List<EvolutionController.SkillView> views = controller.listSkills().block();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", views == null ? 0 : views.size());
            List<Map<String, Object>> rows = new ArrayList<>();
            if (views != null) {
                for (EvolutionController.SkillView v : views) {
                    rows.add(toView(v.skill(), v.telemetry()));
                }
            }
            result.put("skills", rows);
            return result;
        }

        @PostMapping("/skills/{name}/pin")
        public Map<String, Object> pin(@PathVariable String name) {
            Map<String, Object> view = toView(null, controller.pin(name).block());
            dashboard.evolution("evolution.pinned", name);
            return view;
        }

        @PostMapping("/skills/{name}/unpin")
        public Map<String, Object> unpin(@PathVariable String name) {
            Map<String, Object> view = toView(null, controller.unpin(name).block());
            dashboard.evolution("evolution.unpinned", name);
            return view;
        }

        @PostMapping("/skills/{name}/archive")
        public Map<String, Object> archive(@PathVariable String name) {
            Map<String, Object> view = toView(null, controller.archive(name).block());
            dashboard.evolution("evolution.archived", name);
            return view;
        }

        @PostMapping("/curator/run")
        public Map<String, Object> runCurator(
                @RequestParam(name = "dry", defaultValue = "true") boolean dry) {
            UmbrellaConsolidationPlanner.PlanResult result = controller.runCurator(dry).block();
            if (!dry && result != null && result.totalChanged() > 0) {
                dashboard.evolution("evolution.curator-run");
            }
            return resultToJson(result);
        }

        @PostMapping("/curator/lifecycle/run")
        public Map<String, Object> runLifecycle() {
            LifecycleTransitionResult r = controller.runLifecycle().block();
            if (r != null && r.totalChanged() > 0) {
                dashboard.evolution("evolution.lifecycle-run");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runAt", r.runAt().toString());
            out.put("checked", r.checked());
            out.put("markedStale", r.markedStale());
            out.put("archived", r.archived());
            out.put("reactivated", r.reactivated());
            out.put("skippedImmune", r.skippedImmune());
            out.put("totalChanged", r.totalChanged());
            return out;
        }

        private Map<String, Object> resultToJson(UmbrellaConsolidationPlanner.PlanResult r) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dryRun", r.dryRun());
            out.put("totalChanged", r.totalChanged());
            if (r.lifecycle() != null) {
                Map<String, Object> lc = new LinkedHashMap<>();
                lc.put("checked", r.lifecycle().checked());
                lc.put("markedStale", r.lifecycle().markedStale());
                lc.put("archived", r.lifecycle().archived());
                lc.put("reactivated", r.lifecycle().reactivated());
                out.put("lifecycle", lc);
            }
            ConsolidationReport cons = r.consolidation();
            Map<String, Object> consOut = new LinkedHashMap<>();
            consOut.put("dryRun", cons.dryRun());
            consOut.put("runAt", cons.runAt().toString());
            consOut.put("applied", cons.applied().stream().map(Endpoints::stepToJson).toList());
            consOut.put("skipped", cons.skipped().stream().map(Endpoints::stepToJson).toList());
            out.put("consolidation", consOut);
            return out;
        }

        private static Map<String, Object> stepToJson(ConsolidationReport.Step step) {
            Map<String, Object> m = new LinkedHashMap<>();
            CuratorAction a = step.action();
            m.put("kind", a.getClass().getSimpleName());
            m.put("umbrella", a.umbrella());
            m.put("applied", step.applied());
            m.put("noop", step.noop());
            m.put("message", step.message());
            if (a instanceof CuratorAction.MergeIntoUmbrella merge) {
                m.put("siblings", merge.siblings());
                m.put("rationale", merge.rationale());
            } else if (a instanceof CuratorAction.CreateUmbrella create) {
                m.put("siblings", create.siblings());
                m.put("description", create.description());
                m.put("rationale", create.rationale());
            } else if (a instanceof CuratorAction.DemoteToSupport demote) {
                m.put("sibling", demote.sibling());
                m.put("supportKind", demote.supportKind().toString());
                m.put("fileName", demote.fileName());
                m.put("rationale", demote.rationale());
            } else if (a instanceof CuratorAction.Keep keep) {
                m.put("rationale", keep.rationale());
            } else if (a instanceof CuratorAction.Archive arch) {
                m.put("rationale", arch.rationale());
            }
            return m;
        }

        private static Map<String, Object> toView(EvolvedSkill skill, SkillTelemetry t) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (skill != null) {
                m.put("name", skill.name());
                m.put("description", skill.description());
                m.put("category", skill.category());
                m.put("tags", skill.tags());
                m.put("trustLevel", skill.trustLevel().name());
                if (skill.createdAt() != null) m.put("createdAt", skill.createdAt().toString());
                if (skill.updatedAt() != null) m.put("updatedAt", skill.updatedAt().toString());
                m.put("usageCount", skill.usageCount());
            } else if (t != null) {
                m.put("name", t.skillName());
            }
            if (t != null) {
                m.put("state", t.state().name());
                m.put("provenance", t.provenance().name());
                m.put("pinned", t.pinned());
                m.put("useCount", t.useCount());
                m.put("viewCount", t.viewCount());
                m.put("patchCount", t.patchCount());
                if (t.lastUsedAt() != null) m.put("lastUsedAt", t.lastUsedAt().toString());
                if (t.lastViewedAt() != null) m.put("lastViewedAt", t.lastViewedAt().toString());
                if (t.lastPatchedAt() != null) m.put("lastPatchedAt", t.lastPatchedAt().toString());
                if (t.archivedAt() != null) m.put("archivedAt", t.archivedAt().toString());
                if (t.absorbedInto() != null) m.put("absorbedInto", t.absorbedInto());
            }
            return m;
        }
    }
}
