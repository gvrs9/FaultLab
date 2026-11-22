package com.faultlab.order.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts or creates an X-Correlation-Id, exposes it to logs through the ThreadContext,
 * echoes it on the response and writes one structured access-log line per API request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger accessLog = LogManager.getLogger("com.faultlab.order.web.AccessLog");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        long start = System.nanoTime();
        ThreadContext.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.getRequestURI().startsWith("/actuator")) {
                ThreadContext.put("http_status", String.valueOf(response.getStatus()));
                ThreadContext.put("http_method", request.getMethod());
                ThreadContext.put("http_path", request.getRequestURI());
                accessLog.info("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            }
            ThreadContext.remove(MDC_KEY);
            ThreadContext.remove("http_status");
            ThreadContext.remove("http_method");
            ThreadContext.remove("http_path");
            ThreadContext.remove("retry_attempt");
        }
    }
}
