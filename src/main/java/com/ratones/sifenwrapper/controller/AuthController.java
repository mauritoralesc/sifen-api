package com.ratones.sifenwrapper.controller;

import com.ratones.sifenwrapper.dto.auth.LoginRequest;
import com.ratones.sifenwrapper.dto.auth.LoginResponse;
import com.ratones.sifenwrapper.dto.auth.RefreshRequest;
import com.ratones.sifenwrapper.dto.auth.RegisterRequest;
import com.ratones.sifenwrapper.dto.auth.SetupCompanyRequest;
import com.ratones.sifenwrapper.dto.auth.SwitchCompanyRequest;
import com.ratones.sifenwrapper.dto.auth.CompanyMemberInfo;
import com.ratones.sifenwrapper.dto.response.SifenApiResponse;
import com.ratones.sifenwrapper.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<SifenApiResponse<LoginResponse>> register(@RequestBody RegisterRequest request) {
        log.info("POST /auth/register - email={}", request.getEmail());
        LoginResponse response = authService.register(request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Cuenta creada correctamente"));
    }

    @PostMapping("/login")
    public ResponseEntity<SifenApiResponse<LoginResponse>> login(@RequestBody LoginRequest request) {
        log.info("POST /auth/login - email={}", request.getEmail());
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Login exitoso"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<SifenApiResponse<LoginResponse>> refresh(@RequestBody RefreshRequest request) {
        log.info("POST /auth/refresh");
        LoginResponse response = authService.refresh(request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Token renovado"));
    }

    @GetMapping("/companies")
    public ResponseEntity<SifenApiResponse<List<CompanyMemberInfo>>> getMyCompanies() {
        Long userId = getAuthenticatedUserId();
        if (userId == null) {
            throw new IllegalArgumentException("Se requiere autenticación");
        }
        log.info("GET /auth/companies - userId={}", userId);
        List<CompanyMemberInfo> companies = authService.getMyCompanies(userId);
        return ResponseEntity.ok(SifenApiResponse.ok(companies, "Empresas del usuario"));
    }

    @PostMapping("/switch-company")
    public ResponseEntity<SifenApiResponse<LoginResponse>> switchCompany(
            @RequestBody SwitchCompanyRequest request) {
        Long userId = getAuthenticatedUserId();
        if (userId == null) {
            throw new IllegalArgumentException("Se requiere autenticación");
        }
        log.info("POST /auth/switch-company - companyId={}", request.getCompanyId());
        LoginResponse response = authService.switchCompany(userId, request.getCompanyId());
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Empresa seleccionada"));
    }

    private Long getAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
