package com.ratones.sifenwrapper.security.apikey;

import com.ratones.sifenwrapper.entity.ApiKey;
import com.ratones.sifenwrapper.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKeyValue = request.getHeader(API_KEY_HEADER);
        if (apiKeyValue == null || apiKeyValue.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        ApiKey apiKey = apiKeyService.findByRawKey(apiKeyValue);
        if (apiKey == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Verificar expiración
        if (apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.debug("API Key expirada: prefix={}", apiKey.getKeyPrefix());
            filterChain.doFilter(request, response);
            return;
        }

        Long companyId = apiKey.getCompany().getId();
        // API Keys obtienen rol USER para acceso a /invoices/**
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var authToken = new UsernamePasswordAuthenticationToken(
                "apikey:" + apiKey.getId(), companyId, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);

        log.debug("Autenticado via API Key: prefix={}, companyId={}", apiKey.getKeyPrefix(), companyId);
        filterChain.doFilter(request, response);
    }
}
