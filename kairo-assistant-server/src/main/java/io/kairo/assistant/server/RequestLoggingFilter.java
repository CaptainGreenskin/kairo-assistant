package io.kairo.assistant.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class RequestLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final MetricsCollector metrics;

    public RequestLoggingFilter(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpReq
                && response instanceof HttpServletResponse httpResp) {

            String path = httpReq.getRequestURI();
            if (path.startsWith("/api/health") || path.startsWith("/api/metrics")
                    || path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".ico")) {
                chain.doFilter(request, response);
                return;
            }

            String method = httpReq.getMethod();
            metrics.recordRequest();
            metrics.recordEndpointHit(method + " " + path);
            long start = System.currentTimeMillis();
            String clientIp = resolveClientIp(httpReq);

            try {
                chain.doFilter(request, response);
            } finally {
                long elapsed = System.currentTimeMillis() - start;
                int status = httpResp.getStatus();
                metrics.recordStatusCode(status);
                if (status >= 400) metrics.recordError();
                log.info("{} {} {} {}ms [{}]", method, path, status, elapsed, clientIp);
            }
            return;
        }

        chain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
