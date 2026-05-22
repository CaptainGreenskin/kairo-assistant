/*
 * Copyright 2025-2026 the Kairo authors.
 */
package io.kairo.assistant.server.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA fallback so React Router deep links work at the root URL. Spring's default
 * static-resource handler serves {@code /index.html} for {@code /}, but every
 * other React-Router path (e.g. {@code /board}, {@code /evolution/skill/foo})
 * would 404 because no static file exists at those paths.
 *
 * <p>The view controllers below forward 1- and 2-segment non-file paths back to
 * {@code /index.html} where React Router takes over. Two precedence rules keep
 * this from swallowing real endpoints:
 *
 * <ol>
 *   <li>{@code @RestController} mappings win over view controllers, so {@code
 *       /api/*} still hits the real controllers.
 *   <li>The {@code [^.]*} regex skips anything containing a dot — i.e. asset
 *       files like {@code /assets/index-abc.js}, {@code /favicon.ico}.
 * </ol>
 *
 * <p>The legacy chat-only HTML UI that used to live at {@code /} has been
 * moved aside to {@code resources/static-legacy/index.html}; its features are
 * being absorbed into the Console's Chat tab.
 */
@Configuration
public class ConsoleWebUiConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Legacy /cron/* URLs (the previous Console mount-point) redirect to
        // the unified root so existing bookmarks keep working.
        registry.addRedirectViewController("/cron", "/");
        registry.addRedirectViewController("/cron/", "/");

        // Root is handled by Spring's WelcomePageHandlerMapping (auto-serves
        // /index.html). Only fallback the deeper paths.
        registry.addViewController("/{path:[^.]*}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{x:[^.]*}/{y:[^.]*}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{x:[^.]*}/{y:[^.]*}/{z:[^.]*}")
                .setViewName("forward:/index.html");
    }
}
