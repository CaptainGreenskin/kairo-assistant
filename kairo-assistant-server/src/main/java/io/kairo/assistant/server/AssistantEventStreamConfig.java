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

import io.kairo.api.event.KairoEventBus;
import io.kairo.api.event.stream.KairoEventStreamAuthorizer;
import io.kairo.core.event.DefaultKairoEventBus;
import io.kairo.eventstream.DefaultEventStreamService;
import io.kairo.eventstream.EventStreamRegistry;
import io.kairo.eventstream.EventStreamService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@code kairo-event-stream} SPI for kairo-assistant. We bind the bus, registry, and
 * service beans manually instead of relying on {@code kairo-spring-boot-starter-event-stream}
 * because the starter ships a WebFlux SSE/WS controller pair that conflicts with this server's
 * Spring MVC stack. The MVC-native SSE bridge lives in {@link
 * AssistantEventStreamController}.
 *
 * <p>The default authorizer below allows every subscription unconditionally — appropriate for a
 * single-tenant assistant server. Production deployments should override the {@link
 * KairoEventStreamAuthorizer} bean to gate by tenant / API key / source IP.
 */
@Configuration(proxyBeanMethods = false)
public class AssistantEventStreamConfig {

    @Bean
    @ConditionalOnMissingBean(KairoEventBus.class)
    public KairoEventBus kairoEventBus() {
        return new DefaultKairoEventBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventStreamRegistry eventStreamRegistry() {
        return new EventStreamRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(KairoEventStreamAuthorizer.class)
    public KairoEventStreamAuthorizer kairoEventStreamAuthorizer() {
        // Single-tenant assistant — accept every subscription. Override this bean to apply
        // tenant / API-key / source-IP gating in production.
        return request -> KairoEventStreamAuthorizer.AuthorizationDecision.allow();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventStreamService eventStreamService(
            KairoEventBus bus,
            KairoEventStreamAuthorizer authorizer,
            EventStreamRegistry registry) {
        return new DefaultEventStreamService(bus, authorizer, registry);
    }
}
