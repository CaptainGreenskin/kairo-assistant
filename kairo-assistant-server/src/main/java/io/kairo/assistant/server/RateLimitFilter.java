package io.kairo.assistant.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

@Component
public class RateLimitFilter implements Filter {

    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private static final long WINDOW_MS = 60_000;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> requestLog =
            new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpReq
                && response instanceof HttpServletResponse httpResp) {

            String path = httpReq.getRequestURI();
            if (path.startsWith("/api/health") || path.startsWith("/api/status") || path.startsWith("/api/metrics")) {
                chain.doFilter(request, response);
                return;
            }

            String clientKey = resolveClientKey(httpReq);
            if (!tryAcquire(clientKey)) {
                httpResp.setStatus(429);
                httpResp.setContentType("application/json");
                httpResp.getWriter().write(
                        "{\"error\":\"Rate limit exceeded. Max "
                                + MAX_REQUESTS_PER_MINUTE + " requests/minute.\"}");
                return;
            }

            httpResp.setHeader("X-RateLimit-Limit", String.valueOf(MAX_REQUESTS_PER_MINUTE));
            httpResp.setHeader("X-RateLimit-Remaining", String.valueOf(remaining(clientKey)));
        }

        chain.doFilter(request, response);
    }

    private boolean tryAcquire(String clientKey) {
        long now = System.currentTimeMillis();
        var deque = requestLog.computeIfAbsent(clientKey, k -> new ConcurrentLinkedDeque<>());

        while (!deque.isEmpty() && deque.peekFirst() < now - WINDOW_MS) {
            deque.pollFirst();
        }

        if (deque.size() >= MAX_REQUESTS_PER_MINUTE) {
            return false;
        }

        deque.addLast(now);
        return true;
    }

    private int remaining(String clientKey) {
        var deque = requestLog.get(clientKey);
        if (deque == null) return MAX_REQUESTS_PER_MINUTE;
        long now = System.currentTimeMillis();
        while (!deque.isEmpty() && deque.peekFirst() < now - WINDOW_MS) {
            deque.pollFirst();
        }
        return Math.max(0, MAX_REQUESTS_PER_MINUTE - deque.size());
    }

    private String resolveClientKey(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
