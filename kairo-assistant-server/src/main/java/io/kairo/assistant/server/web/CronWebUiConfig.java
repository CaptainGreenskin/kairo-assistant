/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.assistant.server.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Tiny config to make React-Router-style deep links work. Spring's default
 * static-resource handler serves {@code /cron/index.html} for {@code /cron/},
 * but {@code /cron/create} returns 404 because no static file exists at that
 * path. The {@link ViewControllerRegistry} below forwards every {@code /cron/...}
 * request that isn't a real file to the SPA entrypoint, where React Router takes
 * over.
 */
@Configuration
public class CronWebUiConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Single-page-app fallback for the React cron management UI.
        registry.addViewController("/cron").setViewName("forward:/cron/index.html");
        registry.addViewController("/cron/").setViewName("forward:/cron/index.html");
        registry.addViewController("/cron/{path:[^.]*}")
                .setViewName("forward:/cron/index.html");
        registry.addViewController("/cron/{path:[^.]*}/{path2:[^.]*}")
                .setViewName("forward:/cron/index.html");
    }
}
