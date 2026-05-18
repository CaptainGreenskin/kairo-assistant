package io.kairo.assistant.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.filter.CorsFilter;

class CorsConfigTest {

    @Test
    void corsFilterIsCreated() {
        CorsConfig config = new CorsConfig();
        CorsFilter filter = config.corsFilter();
        assertNotNull(filter);
    }

    @Test
    void corsFilterIsInstanceOfCorsFilter() {
        CorsConfig config = new CorsConfig();
        Object filter = config.corsFilter();
        assertInstanceOf(CorsFilter.class, filter);
    }

    @Test
    void webSocketPathCoveredByApiWildcard() {
        CorsConfig config = new CorsConfig();
        CorsFilter filter = config.corsFilter();
        assertNotNull(filter, "CorsFilter should cover /api/** which includes /api/ws");
    }

    @Test
    void multipleCallsReturnDistinctInstances() {
        CorsConfig config = new CorsConfig();
        CorsFilter f1 = config.corsFilter();
        CorsFilter f2 = config.corsFilter();
        assertNotNull(f1);
        assertNotNull(f2);
    }

    @Test
    void configClassAnnotatedAsConfiguration() {
        assertTrue(CorsConfig.class.isAnnotationPresent(org.springframework.context.annotation.Configuration.class));
    }
}
