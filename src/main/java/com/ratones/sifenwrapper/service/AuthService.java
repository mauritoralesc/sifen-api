package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.config.SecurityProperties;
import com.ratones.sifenwrapper.dto.auth.LoginRequest;
import com.ratones.sifenwrapper.dto.auth.LoginResponse;
import com.ratones.sifenwrapper.dto.auth.RefreshRequest;
import com.ratones.sifenwrapper.entity.User;
import com.ratones.sifenwrapper.repository.UserRepository;
import com.ratones.sifenwrapper.security.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndActiveTrue(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Credenciales inválidas"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Credenciales inválidas");
        }

        String accessToken = jwtService.generateAccessToken(
                user.getId(), user.getCompany().getId(), user.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        log.info("Login exitoso para usuario: {} (companyId={})", user.getEmail(), user.getCompany().getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(securityProperties.getJwt().getAccessTokenExpiration())
                .role(user.getRole().name())
                .companyId(user.getCompany().getId())
                .build();
    }

    public LoginResponse refresh(RefreshRequest request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtService.isValidToken(refreshToken)) {
            throw new IllegalArgumentException("Refresh token inválido o expirado");
        }

        String tokenType = jwtService.getTokenType(refreshToken);
        if (!"refresh".equals(tokenType)) {
            throw new IllegalArgumentException("Token no es de tipo refresh");
        }

        Long userId = jwtService.getUserId(refreshToken);
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado o inactivo"));

        String newAccessToken = jwtService.generateAccessToken(
                user.getId(), user.getCompany().getId(), user.getRole().name());
        String newRefreshToken = jwtService.generateRefreshToken(user.getId());

        return LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(securityProperties.getJwt().getAccessTokenExpiration())
                .role(user.getRole().name())
                .companyId(user.getCompany().getId())
                .build();
    }
}
