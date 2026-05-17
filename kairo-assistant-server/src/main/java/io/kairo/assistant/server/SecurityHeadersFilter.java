package io.kairo.assistant.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResp) {
            httpResp.setHeader("X-Content-Type-Options", "nosniff");
            httpResp.setHeader("X-Frame-Options", "DENY");
            httpResp.setHeader("X-XSS-Protection", "1; mode=block");
            httpResp.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            httpResp.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            httpResp.setHeader("Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'unsafe-inline' https://unpkg.com; "
                    + "style-src 'self' 'unsafe-inline' https://unpkg.com; "
                    + "connect-src 'self' ws: wss:; img-src 'self' data:; font-src 'self' https://unpkg.com");
        }
        chain.doFilter(request, response);
    }
}
