package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.config.SecurityProperties;
import com.ratones.sifenwrapper.dto.auth.*;
import com.ratones.sifenwrapper.entity.Company;
import com.ratones.sifenwrapper.entity.User;
import com.ratones.sifenwrapper.entity.UserCompanyMembership;
import com.ratones.sifenwrapper.repository.CompanyRepository;
import com.ratones.sifenwrapper.repository.UserCompanyMembershipRepository;
import com.ratones.sifenwrapper.repository.UserRepository;
import com.ratones.sifenwrapper.security.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final UserCompanyMembershipRepository membershipRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Ya existe una cuenta con ese email");
        }

        User user = userRepository.save(User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .build());

        String accessToken = jwtService.generateNoCompanyToken(user.getId(), User.Role.ADMIN.name());

        log.info("Registro exitoso: {} (id={})", user.getEmail(), user.getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .expiresIn(securityProperties.getJwt().getAccessTokenExpiration())
                .role(User.Role.ADMIN.name())
                .build();
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndActiveTrue(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Credenciales inválidas"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Credenciales inválidas");
        }

        List<UserCompanyMembership> memberships = membershipRepository.findAllByUserIdAndActiveTrue(user.getId());

        // Determinar rol: ADMIN si tiene al menos una membresía ADMIN, si no USER, si no tiene empresas ADMIN
        String role = memberships.stream()
                .anyMatch(m -> m.getRole() == User.Role.ADMIN)
                ? User.Role.ADMIN.name()
                : (memberships.isEmpty() ? User.Role.ADMIN.name() : User.Role.USER.name());

        String accessToken = jwtService.generateNoCompanyToken(user.getId(), role);

        log.info("Login exitoso: {} ({} empresas disponibles)", user.getEmail(), memberships.size());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .expiresIn(securityProperties.getJwt().getAccessTokenExpiration())
                .role(role)
                .build();
    }

    public List<CompanyMemberInfo> getMyCompanies(Long userId) {
        return membershipRepository.findAllByUserIdAndActiveTrue(userId).stream()
                .map(m -> CompanyMemberInfo.builder()
                        .companyId(m.getCompany().getId())
                        .nombre(m.getCompany().getNombre())
                        .ruc(m.getCompany().getRuc())
                        .role(m.getRole().name())
                        .build())
                .toList();
    }

    @Transactional
    public LoginResponse setupCompany(String selectionToken, SetupCompanyRequest request) {
        if (!jwtService.isValidToken(selectionToken)) {
            throw new IllegalArgumentException("Selection token inválido o expirado");
        }
        if (!"selection".equals(jwtService.getTokenType(selectionToken))) {
            throw new IllegalArgumentException("Token no es de tipo selection");
        }

        Long userId = jwtService.getUserId(selectionToken);
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado o inactivo"));

        String ambiente = (request.getAmbiente() != null && !request.getAmbiente().isBlank())
                ? request.getAmbiente().toUpperCase()
                : "DEV";

        Company company = companyRepository.save(Company.builder()
                .nombre(request.getNombre())
                .ruc(request.getRuc())
                .dv(request.getDv())
                .ambiente(ambiente)
                .build());

        membershipRepository.save(UserCompanyMembership.builder()
                .user(user)
                .company(company)
                .role(User.Role.ADMIN)
                .build());

        String accessToken = jwtService.generateAccessToken(user.getId(), company.getId(), User.Role.ADMIN.name());
        String refreshToken = jwtService.generateRefreshToken(user.getId(), company.getId());

        log.info("Setup company: userId={} → empresa '{}' (id={})", userId, company.getNombre(), company.getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(securityProperties.getJwt().getAccessTokenExpiration())
                .role(User.Role.ADMIN.name())
                .companyId(company.getId())
                .build();
    }

    public LoginResponse switchCompany(Long userId, Long companyId) {
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado o inactivo"));

        UserCompanyMembership membership = membershipRepository
                .findByUserIdAndCompanyIdAndActiveTrue(userId, companyId)
                .orElseThrow(() -> new IllegalArgumentException("El usuario no tiene acceso a esa empresa"));

        String accessToken = jwtService.generateAccessToken(
                user.getId(), companyId, membership.getRole().name());
        String refreshToken = jwtService.generateRefreshToken(user.getId(), companyId);

        log.info("Switch company: userId={} → companyId={}", userId, companyId);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(securityProperties.getJwt().getAccessTokenExpiration())
                .role(membership.getRole().name())
                .companyId(companyId)
                .build();
    }

    public LoginResponse refresh(RefreshRequest request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtService.isValidToken(refreshToken)) {
            throw new IllegalArgumentException("Refresh token inválido o expirado");
        }
        if (!"refresh".equals(jwtService.getTokenType(refreshToken))) {
            throw new IllegalArgumentException("Token no es de tipo refresh");
        }

        Long userId = jwtService.getUserId(refreshToken);
        Long companyId = jwtService.getCompanyId(refreshToken);

        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado o inactivo"));

        UserCompanyMembership membership = membershipRepository
                .findByUserIdAndCompanyIdAndActiveTrue(userId, companyId)
                .orElseThrow(() -> new IllegalArgumentException("La membresía del usuario en esa empresa ya no está activa"));

        String newAccessToken = jwtService.generateAccessToken(
                user.getId(), companyId, membership.getRole().name());
        String newRefreshToken = jwtService.generateRefreshToken(user.getId(), companyId);

        return LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(securityProperties.getJwt().getAccessTokenExpiration())
                .role(membership.getRole().name())
                .companyId(companyId)
                .build();
    }
}
