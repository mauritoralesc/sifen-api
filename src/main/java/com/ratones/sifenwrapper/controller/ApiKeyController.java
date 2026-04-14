package com.ratones.sifenwrapper.controller;

import com.ratones.sifenwrapper.dto.apikey.ApiKeyResponse;
import com.ratones.sifenwrapper.dto.apikey.CreateApiKeyRequest;
import com.ratones.sifenwrapper.dto.response.SifenApiResponse;
import com.ratones.sifenwrapper.security.TenantContext;
import com.ratones.sifenwrapper.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @PostMapping
    public ResponseEntity<SifenApiResponse<ApiKeyResponse>> create(@RequestBody CreateApiKeyRequest request) {
        Long companyId = TenantContext.get();
        log.info("POST /api-keys - name={}, companyId={}", request.getName(), companyId);
        ApiKeyResponse response = apiKeyService.create(companyId, request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "API Key creada. Guarde el valor, no se mostrará de nuevo."));
    }

    @GetMapping
    public ResponseEntity<SifenApiResponse<List<ApiKeyResponse>>> findAll() {
        Long companyId = TenantContext.get();
        log.info("GET /api-keys - companyId={}", companyId);
        return ResponseEntity.ok(SifenApiResponse.ok(apiKeyService.findAllByCompany(companyId)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SifenApiResponse<Void>> revoke(@PathVariable Long id) {
        Long companyId = TenantContext.get();
        log.info("DELETE /api-keys/{} - companyId={}", id, companyId);
        apiKeyService.revoke(companyId, id);
        return ResponseEntity.ok(SifenApiResponse.ok(null, "API Key revocada"));
    }
}
