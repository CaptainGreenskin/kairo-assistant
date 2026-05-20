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

import io.kairo.api.event.KairoEvent;
import io.kairo.api.event.KairoEventBus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Thin façade for emitting dashboard-grade {@link KairoEvent}s onto the bus. Keeps the publish-site
 * code (CronController, EvolutionRestController) one-liner-clean, and provides domain constants so
 * the SSE consumer on the React side has a stable vocabulary to subscribe against.
 *
 * <p>Domains used by this server:
 *
 * <ul>
 *   <li>{@code cron} — task lifecycle (cron.created / cron.edited / cron.paused / cron.resumed /
 *       cron.triggered / cron.deleted)
 *   <li>{@code evolution} — skill governance lifecycle (evolution.pinned / evolution.unpinned /
 *       evolution.archived / evolution.curator-run / evolution.lifecycle-run)
 * </ul>
 *
 * <p>Payloads are intentionally lightweight ({@code id} + change-kind). The dashboard reacts by
 * invalidating its React-Query cache and re-fetching the authoritative state via REST — same
 * "notify, don't ship full state" pattern as hermes-hudui's file-watcher broadcast.
 */
@Component
public class DashboardEventPublisher {

    public static final String DOMAIN_CRON = "cron";
    public static final String DOMAIN_EVOLUTION = "evolution";

    private final KairoEventBus bus;

    public DashboardEventPublisher(KairoEventBus bus) {
        this.bus = bus;
    }

    /** Publish a cron-domain event with an {@code id} attribute. */
    public void cron(String eventType, String id) {
        bus.publish(KairoEvent.of(DOMAIN_CRON, eventType, idAttr(id)));
    }

    /** Publish an evolution-domain event with an {@code id} attribute. */
    public void evolution(String eventType, String id) {
        bus.publish(KairoEvent.of(DOMAIN_EVOLUTION, eventType, idAttr(id)));
    }

    /** Publish an evolution-domain event without an id (e.g. global curator run). */
    public void evolution(String eventType) {
        bus.publish(KairoEvent.of(DOMAIN_EVOLUTION, eventType, Map.of()));
    }

    /** Generic escape hatch when callers want to attach extra attributes. */
    public void publish(String domain, String eventType, Map<String, Object> attributes) {
        bus.publish(KairoEvent.of(domain, eventType, attributes));
    }

    private static Map<String, Object> idAttr(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) m.put("id", id);
        return m;
    }
}
