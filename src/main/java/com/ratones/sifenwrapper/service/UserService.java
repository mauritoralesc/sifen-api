package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.dto.user.*;
import com.ratones.sifenwrapper.entity.Company;
import com.ratones.sifenwrapper.entity.User;
import com.ratones.sifenwrapper.entity.UserCompanyMembership;
import com.ratones.sifenwrapper.repository.CompanyRepository;
import com.ratones.sifenwrapper.repository.UserCompanyMembershipRepository;
import com.ratones.sifenwrapper.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final UserCompanyMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;

    /** Crea un usuario nuevo y lo asocia a la empresa indicada. */
    @Transactional
    public UserResponse createInCompany(Long companyId, CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Ya existe un usuario con ese email");
        }

        Company company = companyRepository.findByIdAndActiveTrue(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Empresa no encontrada"));

        User.Role role = parseRole(request.getRole());

        User user = userRepository.save(User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .build());

        membershipRepository.save(UserCompanyMembership.builder()
                .user(user)
                .company(company)
                .role(role)
                .build());

        return toResponse(user, role);
    }

    /** Lista usuarios con membresía activa en la empresa indicada. */
    public List<UserResponse> findByCompany(Long companyId) {
        return membershipRepository.findAllByCompanyIdAndActiveTrue(companyId).stream()
                .map(m -> toResponse(m.getUser(), m.getRole()))
                .toList();
    }

    /** Detalle de un usuario verificando que tenga membresía en la empresa indicada. */
    public UserResponse findInCompany(Long companyId, Long userId) {
        UserCompanyMembership m = membershipRepository
                .findByUserIdAndCompanyIdAndActiveTrue(userId, companyId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado en esta empresa"));
        return toResponse(m.getUser(), m.getRole());
    }

    /** Actualiza datos básicos del usuario (solo si tiene membresía en la empresa). */
    @Transactional
    public UserResponse updateInCompany(Long companyId, Long userId, UpdateUserRequest request) {
        UserCompanyMembership m = membershipRepository
                .findByUserIdAndCompanyIdAndActiveTrue(userId, companyId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado en esta empresa"));

        User user = m.getUser();
        if (request.getFullName() != null) {
            user.setFullName(request.getFullName());
        }
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new IllegalArgumentException("Ya existe un usuario con ese email");
            }
            user.setEmail(request.getEmail());
        }
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        userRepository.save(user);
        return toResponse(user, m.getRole());
    }

    /** Desactiva la membresía del usuario en la empresa (no borra el usuario global). */
    @Transactional
    public void removeMembership(Long companyId, Long userId) {
        UserCompanyMembership m = membershipRepository
                .findByUserIdAndCompanyIdAndActiveTrue(userId, companyId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado en esta empresa"));
        m.setActive(false);
        membershipRepository.save(m);
    }

    /** Agrega un usuario existente a una empresa. */
    @Transactional
    public MembershipResponse addMember(Long companyId, AddMemberRequest request) {
        Company company = companyRepository.findByIdAndActiveTrue(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Empresa no encontrada"));

        User user = userRepository.findByEmailAndActiveTrue(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("No existe usuario activo con ese email"));

        if (membershipRepository.existsByUserIdAndCompanyIdAndActiveTrue(user.getId(), companyId)) {
            throw new IllegalArgumentException("El usuario ya es miembro de esta empresa");
        }

        User.Role role = parseRole(request.getRole());

        UserCompanyMembership saved = membershipRepository.save(UserCompanyMembership.builder()
                .user(user)
                .company(company)
                .role(role)
                .build());

        return toMembershipResponse(saved);
    }

    /** Lista membresías activas de una empresa. */
    public List<MembershipResponse> listMembers(Long companyId) {
        return membershipRepository.findAllByCompanyIdAndActiveTrue(companyId).stream()
                .map(this::toMembershipResponse)
                .toList();
    }

    // --- helpers ---

    private User.Role parseRole(String role) {
        if (role == null) return User.Role.USER;
        try {
            return User.Role.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Rol inválido: " + role + ". Valores válidos: ADMIN, USER");
        }
    }

    private UserResponse toResponse(User user, User.Role role) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(role.name())
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    private MembershipResponse toMembershipResponse(UserCompanyMembership m) {
        return MembershipResponse.builder()
                .userId(m.getUser().getId())
                .email(m.getUser().getEmail())
                .fullName(m.getUser().getFullName())
                .role(m.getRole().name())
                .active(m.isActive())
                .memberSince(m.getCreatedAt())
                .build();
    }
}
