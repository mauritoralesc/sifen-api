package com.ratones.sifenwrapper.controller;

import com.ratones.sifenwrapper.dto.auth.LoginRequest;
import com.ratones.sifenwrapper.dto.auth.LoginResponse;
import com.ratones.sifenwrapper.dto.auth.RefreshRequest;
import com.ratones.sifenwrapper.dto.response.SifenApiResponse;
import com.ratones.sifenwrapper.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
}
