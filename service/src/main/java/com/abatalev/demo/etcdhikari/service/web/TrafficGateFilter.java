package com.abatalev.demo.etcdhikari.service.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.abatalev.demo.etcdhikari.service.etcd.EtcdPoolConfigSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Гейт трафика: пока конфигурация из etcd не получена, /api/work отвечает 503 в той же форме,
 * что и контроллер (WorkResponse), а наблюдение (метрики, health) продолжает работать — иначе
 * неготовый инстанс не был бы виден.
 */
@Component
public class TrafficGateFilter extends OncePerRequestFilter {

    private final EtcdPoolConfigSource configSource;
    private final ObjectMapper objectMapper;

    public TrafficGateFilter(EtcdPoolConfigSource configSource, ObjectMapper objectMapper) {
        this.configSource = configSource;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/work".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!configSource.isTrafficAllowed()) {
            String reason = configSource.notReadyReason();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            // charset обязателен: иначе writer пишет в ISO-8859-1 и кириллица превращается в '?'.
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                    "ok", false,
                    "error", reason == null ? "конфигурация не получена" : reason)));
            return;
        }
        filterChain.doFilter(request, response);
    }
}