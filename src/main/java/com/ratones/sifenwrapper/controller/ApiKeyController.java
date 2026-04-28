package com.ratones.sifenwrapper.controller;

import com.ratones.sifenwrapper.dto.apikey.ApiKeyResponse;
import com.ratones.sifenwrapper.dto.apikey.CreateApiKeyRequest;
import com.ratones.sifenwrapper.dto.response.SifenApiResponse;
import com.ratones.sifenwrapper.repository.UserCompanyMembershipRepository;
import com.ratones.sifenwrapper.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/companies/{companyId}/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final UserCompanyMembershipRepository membershipRepository;

    @PostMapping
    public ResponseEntity<SifenApiResponse<ApiKeyResponse>> create(
            @PathVariable Long companyId,
            @RequestBody CreateApiKeyRequest request) {
        requireMembership(companyId);
        log.info("POST /companies/{}/api-keys - name={}", companyId, request.getName());
        ApiKeyResponse response = apiKeyService.create(companyId, request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "API Key creada. Guarde el valor, no se mostrará de nuevo."));
    }

    @GetMapping
    public ResponseEntity<SifenApiResponse<List<ApiKeyResponse>>> findAll(@PathVariable Long companyId) {
        requireMembership(companyId);
        log.info("GET /companies/{}/api-keys", companyId);
        return ResponseEntity.ok(SifenApiResponse.ok(apiKeyService.findAllByCompany(companyId)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SifenApiResponse<Void>> revoke(
            @PathVariable Long companyId,
            @PathVariable Long id) {
        requireMembership(companyId);
        log.info("DELETE /companies/{}/api-keys/{}", companyId, id);
        apiKeyService.revoke(companyId, id);
        return ResponseEntity.ok(SifenApiResponse.ok(null, "API Key revocada"));
    }

    private void requireMembership(Long companyId) {
        Long userId = getAuthenticatedUserId();
        if (userId == null || !membershipRepository.existsByUserIdAndCompanyIdAndActiveTrue(userId, companyId)) {
            throw new org.springframework.security.access.AccessDeniedException("No tiene acceso a esta empresa");
        }
    }

    private Long getAuthenticatedUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
