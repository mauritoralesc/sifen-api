package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.dto.apikey.ApiKeyResponse;
import com.ratones.sifenwrapper.dto.apikey.CreateApiKeyRequest;
import com.ratones.sifenwrapper.entity.ApiKey;
import com.ratones.sifenwrapper.entity.Company;
import com.ratones.sifenwrapper.repository.ApiKeyRepository;
import com.ratones.sifenwrapper.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX_LIVE = "sw_live_";
    private static final int RAW_KEY_LENGTH = 32;

    private final ApiKeyRepository apiKeyRepository;
    private final CompanyRepository companyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public ApiKeyResponse create(Long companyId, CreateApiKeyRequest request) {
        Company company = companyRepository.findByIdAndActiveTrue(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Empresa no encontrada: " + companyId));

        // Generar key aleatorio
        byte[] randomBytes = new byte[RAW_KEY_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String rawKey = PREFIX_LIVE + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String keyHash = sha256(rawKey);
        String keyPrefix = rawKey.substring(0, Math.min(16, rawKey.length()));

        ApiKey apiKey = ApiKey.builder()
                .company(company)
                .keyHash(keyHash)
                .keyPrefix(keyPrefix)
                .name(request.getName())
                .build();

        apiKey = apiKeyRepository.save(apiKey);
        log.info("API Key creada: prefix={} para companyId={}", keyPrefix, companyId);

        return toResponse(apiKey, rawKey);
    }

    public List<ApiKeyResponse> findAllByCompany(Long companyId) {
        return apiKeyRepository.findAllByCompanyIdAndActiveTrue(companyId).stream()
                .map(k -> toResponse(k, null))
                .toList();
    }

    @Transactional
    public void revoke(Long companyId, Long apiKeyId) {
        ApiKey apiKey = apiKeyRepository.findById(apiKeyId)
                .orElseThrow(() -> new IllegalArgumentException("API Key no encontrada: " + apiKeyId));

        if (!apiKey.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("API Key no pertenece a la empresa");
        }

        apiKey.setActive(false);
        apiKeyRepository.save(apiKey);
        log.info("API Key revocada: id={}", apiKeyId);
    }

    /**
     * Busca un API Key activo por el hash del raw key.
     */
    public ApiKey findByRawKey(String rawKey) {
        String hash = sha256(rawKey);
        return apiKeyRepository.findByKeyHashAndActiveTrue(hash).orElse(null);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error computing SHA-256", e);
        }
    }

    private ApiKeyResponse toResponse(ApiKey apiKey, String rawKey) {
        return ApiKeyResponse.builder()
                .id(apiKey.getId())
                .keyPrefix(apiKey.getKeyPrefix())
                .name(apiKey.getName())
                .active(apiKey.isActive())
                .expiresAt(apiKey.getExpiresAt())
                .createdAt(apiKey.getCreatedAt())
                .rawKey(rawKey)
                .build();
    }
}
