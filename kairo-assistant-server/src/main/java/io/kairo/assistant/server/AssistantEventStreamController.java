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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.api.event.KairoEvent;
import io.kairo.api.event.stream.BackpressurePolicy;
import io.kairo.api.event.stream.EventStreamFilter;
import io.kairo.api.event.stream.EventStreamSubscription;
import io.kairo.api.event.stream.EventStreamSubscriptionRequest;
import io.kairo.eventstream.EventStreamAuthorizationException;
import io.kairo.eventstream.EventStreamService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * MVC-native SSE projection of {@link io.kairo.api.event.KairoEventBus}. Dashboards (the React
 * cron + evolution console) subscribe here to get push notifications of state changes and
 * invalidate their React-Query caches instead of polling.
 *
 * <p>Why hand-rolled instead of {@code kairo-spring-boot-starter-event-stream}? The starter ships a
 * WebFlux-based SSE controller; this server runs Spring MVC and mixing the two stacks adds
 * complexity for zero benefit at this scale. We reuse the rest of the SPI — {@link
 * EventStreamService} / {@link EventStreamSubscription} / {@link EventStreamFilter} / {@link
 * io.kairo.api.event.stream.KairoEventStreamAuthorizer} — verbatim.
 *
 * <p>Wire protocol:
 *
 * <ul>
 *   <li>{@code GET /api/events/stream} — opens an SSE stream. Optional query params: {@code
 *       domain=cron&domain=evolution}, {@code eventType=cron.created}, {@code bufferCapacity=64},
 *       {@code policy=BUFFER_DROP_OLDEST}.
 *   <li>Each event is serialized as {@code event: kairo} with JSON payload {@code {eventId,
 *       timestamp, domain, eventType, attributes}}. The opaque {@code payload} field is omitted
 *       (it's a Java reference, not JSON-serializable in general).
 *   <li>A {@code :keepalive} comment is emitted every 25 s so reverse-proxies don't time the
 *       connection out.
 * </ul>
 */
@RestController
@RequestMapping("/api/events")
public class AssistantEventStreamController {

    private static final Logger log = LoggerFactory.getLogger(AssistantEventStreamController.class);
    private static final long EMITTER_TIMEOUT_MS = 0L; // never time out — we manage lifecycle
    private static final long KEEPALIVE_INTERVAL_MS = 25_000L;

    private final EventStreamService service;
    private final ObjectMapper mapper;
    private final Executor keepaliveExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "kairo-sse-keepalive");
                        t.setDaemon(true);
                        return t;
                    });
    private final ConcurrentHashMap<SseEmitter, Long> liveEmitters = new ConcurrentHashMap<>();

    public AssistantEventStreamController(EventStreamService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
        startKeepaliveLoop();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(name = "domain", required = false) List<String> domains,
            @RequestParam(name = "eventType", required = false) List<String> eventTypes,
            @RequestParam(name = "bufferCapacity", required = false) Integer bufferCapacity,
            @RequestParam(name = "policy", required = false) String policyName,
            HttpServletRequest request) {

        EventStreamFilter filter = composeFilter(domains, eventTypes);
        BackpressurePolicy policy = parsePolicy(policyName);
        int capacity = bufferCapacity == null || bufferCapacity <= 0 ? 256 : bufferCapacity;

        EventStreamSubscriptionRequest spi =
                new EventStreamSubscriptionRequest(
                        filter, policy, capacity, extractHeaders(request));

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        EventStreamSubscription subscription;
        try {
            subscription = service.subscribe(spi);
        } catch (EventStreamAuthorizationException denied) {
            // SseEmitter has no clean denied-handshake path before headers flush — propagate
            // so the @ExceptionHandler below returns the proper 403.
            throw denied;
        }

        Disposable disposable =
                subscription
                        .events()
                        .subscribe(
                                event -> sendOrCancel(emitter, event, subscription),
                                err -> {
                                    log.warn("SSE subscription error: {}", err.toString());
                                    completeQuietly(emitter, subscription);
                                },
                                () -> completeQuietly(emitter, subscription));

        emitter.onCompletion(() -> {
            subscription.cancel();
            disposable.dispose();
            liveEmitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            subscription.cancel();
            disposable.dispose();
            liveEmitters.remove(emitter);
        });
        emitter.onError(t -> {
            subscription.cancel();
            disposable.dispose();
            liveEmitters.remove(emitter);
        });

        liveEmitters.put(emitter, System.currentTimeMillis());
        log.debug(
                "SSE subscription opened: id={} filterDomains={} eventTypes={} active={}",
                subscription.id(),
                domains,
                eventTypes,
                liveEmitters.size());
        return emitter;
    }

    @ExceptionHandler(EventStreamAuthorizationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String onDenied(EventStreamAuthorizationException ex) {
        return ex.getMessage() == null ? "forbidden" : ex.getMessage();
    }

    // ----- internals -----

    private void sendOrCancel(
            SseEmitter emitter, KairoEvent event, EventStreamSubscription sub) {
        try {
            String json = mapper.writeValueAsString(toDto(event));
            emitter.send(SseEmitter.event().name("kairo").id(event.eventId()).data(json));
        } catch (IOException io) {
            // Client disconnected mid-send — clean shutdown.
            sub.cancel();
            liveEmitters.remove(emitter);
        } catch (Exception e) {
            log.warn("Failed to push KairoEvent {}: {}", event.eventId(), e.getMessage());
        }
    }

    private void completeQuietly(SseEmitter emitter, EventStreamSubscription sub) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // emitter already closed
        }
        sub.cancel();
        liveEmitters.remove(emitter);
    }

    private void startKeepaliveLoop() {
        Thread t =
                new Thread(
                        () -> {
                            while (!Thread.currentThread().isInterrupted()) {
                                try {
                                    Thread.sleep(KEEPALIVE_INTERVAL_MS);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                for (SseEmitter emitter : liveEmitters.keySet()) {
                                    try {
                                        emitter.send(SseEmitter.event().comment("keepalive"));
                                    } catch (Exception e) {
                                        // emitter likely gone; cleanup happens in send error path
                                        liveEmitters.remove(emitter);
                                    }
                                }
                            }
                        },
                        "kairo-sse-keepalive-loop");
        t.setDaemon(true);
        t.start();
    }

    private static EventStreamFilter composeFilter(
            List<String> domains, List<String> eventTypes) {
        EventStreamFilter filter = EventStreamFilter.acceptAll();
        if (domains != null && !domains.isEmpty()) {
            filter = filter.and(EventStreamFilter.byDomain(domains.toArray(new String[0])));
        }
        if (eventTypes != null && !eventTypes.isEmpty()) {
            filter = filter.and(EventStreamFilter.byEventType(eventTypes.toArray(new String[0])));
        }
        return filter;
    }

    private static BackpressurePolicy parsePolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return BackpressurePolicy.BUFFER_DROP_OLDEST;
        }
        try {
            return BackpressurePolicy.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BackpressurePolicy.BUFFER_DROP_OLDEST;
        }
    }

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> out = new HashMap<>();
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String n = names.nextElement();
            String v = request.getHeader(n);
            if (v != null) {
                out.put(n.toLowerCase(Locale.ROOT), v);
            }
        }
        return out;
    }

    private static Map<String, Object> toDto(KairoEvent event) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("eventId", event.eventId());
        m.put("timestamp", event.timestamp().toString());
        m.put("domain", event.domain());
        m.put("eventType", event.eventType());
        m.put("attributes", event.attributes());
        return m;
    }
}
