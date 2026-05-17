package io.kairo.assistant.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class ApiKeyFilter implements Filter {

    private final String apiKey;

    public ApiKeyFilter() {
        this(System.getenv("KAIRO_API_KEY"));
    }

    ApiKeyFilter(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (apiKey == null || apiKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (request instanceof HttpServletRequest httpReq
                && response instanceof HttpServletResponse httpResp) {

            String path = httpReq.getRequestURI();
            if (path.equals("/") || path.startsWith("/index.html")
                    || path.startsWith("/api/health")
                    || path.startsWith("/api/docs") || path.startsWith("/api/openapi")
                    || path.endsWith(".css") || path.endsWith(".js")
                    || path.endsWith(".ico")) {
                chain.doFilter(request, response);
                return;
            }

            String provided = httpReq.getHeader("Authorization");
            if (provided != null && provided.startsWith("Bearer ")) {
                provided = provided.substring(7);
            } else {
                provided = httpReq.getParameter("api_key");
            }

            if (!apiKey.equals(provided)) {
                httpResp.setStatus(401);
                httpResp.setContentType("application/json");
                httpResp.getWriter().write("{\"error\":\"Unauthorized. Provide API key via Authorization: Bearer <key> header.\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
