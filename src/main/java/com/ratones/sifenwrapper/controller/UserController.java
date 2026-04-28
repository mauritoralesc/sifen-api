package com.ratones.sifenwrapper.controller;

import com.ratones.sifenwrapper.dto.response.SifenApiResponse;
import com.ratones.sifenwrapper.dto.user.CreateUserRequest;
import com.ratones.sifenwrapper.dto.user.UpdateUserRequest;
import com.ratones.sifenwrapper.dto.user.UserResponse;
import com.ratones.sifenwrapper.repository.UserCompanyMembershipRepository;
import com.ratones.sifenwrapper.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/companies/{companyId}/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserCompanyMembershipRepository membershipRepository;

    @PostMapping
    public ResponseEntity<SifenApiResponse<UserResponse>> create(
            @PathVariable Long companyId,
            @RequestBody CreateUserRequest request) {
        requireMembership(companyId);
        log.info("POST /companies/{}/users - email={}", companyId, request.getEmail());
        UserResponse response = userService.createInCompany(companyId, request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Usuario creado correctamente"));
    }

    @GetMapping
    public ResponseEntity<SifenApiResponse<List<UserResponse>>> findAll(@PathVariable Long companyId) {
        requireMembership(companyId);
        log.info("GET /companies/{}/users", companyId);
        return ResponseEntity.ok(SifenApiResponse.ok(userService.findByCompany(companyId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SifenApiResponse<UserResponse>> findById(
            @PathVariable Long companyId,
            @PathVariable Long id) {
        requireMembership(companyId);
        log.info("GET /companies/{}/users/{}", companyId, id);
        return ResponseEntity.ok(SifenApiResponse.ok(userService.findInCompany(companyId, id)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<SifenApiResponse<UserResponse>> update(
            @PathVariable Long companyId,
            @PathVariable Long id,
            @RequestBody UpdateUserRequest request) {
        requireMembership(companyId);
        log.info("PATCH /companies/{}/users/{}", companyId, id);
        UserResponse response = userService.updateInCompany(companyId, id, request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Usuario actualizado"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SifenApiResponse<Void>> remove(
            @PathVariable Long companyId,
            @PathVariable Long id) {
        requireMembership(companyId);
        log.info("DELETE /companies/{}/users/{}", companyId, id);
        userService.removeMembership(companyId, id);
        return ResponseEntity.ok(SifenApiResponse.ok(null, "Membresía del usuario desactivada"));
    }

    private void requireMembership(Long companyId) {
        Long userId = getAuthenticatedUserId();
        if (userId == null || !membershipRepository.existsByUserIdAndCompanyIdAndActiveTrue(userId, companyId)) {
            throw new AccessDeniedException("No tiene acceso a esta empresa");
        }
    }

    private Long getAuthenticatedUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
