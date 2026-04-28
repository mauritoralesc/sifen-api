package com.ratones.sifenwrapper.controller;

import com.ratones.sifenwrapper.dto.company.*;
import com.ratones.sifenwrapper.dto.request.ParamsDTO;
import com.ratones.sifenwrapper.dto.response.SifenApiResponse;
import com.ratones.sifenwrapper.dto.user.AddMemberRequest;
import com.ratones.sifenwrapper.dto.user.MembershipResponse;
import com.ratones.sifenwrapper.repository.UserCompanyMembershipRepository;
import com.ratones.sifenwrapper.service.CertificateService;
import com.ratones.sifenwrapper.service.CompanyService;
import com.ratones.sifenwrapper.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;
    private final CertificateService certificateService;
    private final UserService userService;
    private final UserCompanyMembershipRepository membershipRepository;

    @PostMapping
    public ResponseEntity<SifenApiResponse<CompanyResponse>> create(@RequestBody CreateCompanyRequest request) {
        log.info("POST /companies - nombre={}, ruc={}", request.getNombre(), request.getRuc());
        Long creatorUserId = getAuthenticatedUserId();
        CompanyResponse response = companyService.create(request, creatorUserId);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Empresa creada correctamente"));
    }

    @GetMapping
    public ResponseEntity<SifenApiResponse<List<CompanyResponse>>> findAll() {
        Long userId = getAuthenticatedUserId();
        log.info("GET /companies - userId={}", userId);
        List<CompanyResponse> companies = (userId != null)
                ? companyService.findAllByUser(userId)
                : companyService.findAll();
        return ResponseEntity.ok(SifenApiResponse.ok(companies));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> findById(@PathVariable Long id) {
        log.info("GET /companies/{}", id);
        requireMembership(id);
        return ResponseEntity.ok(SifenApiResponse.ok(companyService.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> update(
            @PathVariable Long id, @RequestBody UpdateCompanyRequest request) {
        log.info("PUT /companies/{}", id);
        requireMembership(id);
        return ResponseEntity.ok(SifenApiResponse.ok(companyService.update(id, request), "Empresa actualizada"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SifenApiResponse<Void>> deactivate(@PathVariable Long id) {
        log.info("DELETE /companies/{}", id);
        requireMembership(id);
        companyService.deactivate(id);
        return ResponseEntity.ok(SifenApiResponse.ok(null, "Empresa desactivada"));
    }

    @PostMapping(value = "/{id}/certificate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SifenApiResponse<CompanyResponse>> uploadCertificate(
            @PathVariable Long id,
            @RequestParam("certificate") MultipartFile file,
            @RequestParam("password") String password) throws IOException {
        log.info("POST /companies/{}/certificate - filename={}", id, file.getOriginalFilename());
        requireMembership(id);
        byte[] pfxBytes = file.getBytes();
        certificateService.validate(pfxBytes, password);
        CompanyResponse response = companyService.uploadCertificate(id, pfxBytes, password);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Certificado cargado correctamente"));
    }

    @DeleteMapping("/{id}/certificate")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> deleteCertificate(@PathVariable Long id) {
        log.info("DELETE /companies/{}/certificate", id);
        requireMembership(id);
        return ResponseEntity.ok(SifenApiResponse.ok(companyService.deleteCertificate(id), "Certificado eliminado"));
    }

    @PutMapping("/{id}/csc")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> updateCsc(
            @PathVariable Long id, @RequestBody UpdateCscRequest request) {
        log.info("PUT /companies/{}/csc", id);
        requireMembership(id);
        return ResponseEntity.ok(SifenApiResponse.ok(companyService.updateCsc(id, request), "CSC actualizado"));
    }

    @PutMapping("/{id}/emisor")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> updateEmisorConfig(
            @PathVariable Long id, @RequestBody ParamsDTO emisorConfig) {
        log.info("PUT /companies/{}/emisor", id);
        requireMembership(id);
        return ResponseEntity.ok(SifenApiResponse.ok(
                companyService.updateEmisorConfig(id, emisorConfig),
                "Configuración de emisor actualizada"));
    }

    @GetMapping("/{id}/emisor")
    public ResponseEntity<SifenApiResponse<ParamsDTO>> getEmisorConfig(@PathVariable Long id) {
        log.info("GET /companies/{}/emisor", id);
        requireMembership(id);
        ParamsDTO config = companyService.getEmisorConfig(id);
        if (config == null) {
            return ResponseEntity.ok(SifenApiResponse.ok(null, "La empresa no tiene configuración de emisor"));
        }
        return ResponseEntity.ok(SifenApiResponse.ok(config));
    }

    // --- Members management ---

    @PostMapping("/{id}/members")
    public ResponseEntity<SifenApiResponse<MembershipResponse>> addMember(
            @PathVariable Long id, @RequestBody AddMemberRequest request) {
        requireMembership(id);
        log.info("POST /companies/{}/members - email={}", id, request.getEmail());
        MembershipResponse response = userService.addMember(id, request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Miembro agregado correctamente"));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<SifenApiResponse<List<MembershipResponse>>> listMembers(@PathVariable Long id) {
        requireMembership(id);
        log.info("GET /companies/{}/members", id);
        return ResponseEntity.ok(SifenApiResponse.ok(userService.listMembers(id)));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<SifenApiResponse<Void>> removeMember(
            @PathVariable Long id, @PathVariable Long userId) {
        requireMembership(id);
        log.info("DELETE /companies/{}/members/{}", id, userId);
        userService.removeMembership(id, userId);
        return ResponseEntity.ok(SifenApiResponse.ok(null, "Membresía desactivada correctamente"));
    }

    /** Verifica que el usuario autenticado tenga membresía activa en la empresa indicada. */
    private void requireMembership(Long companyId) {
        Long userId = getAuthenticatedUserId();
        if (userId == null || !membershipRepository.existsByUserIdAndCompanyIdAndActiveTrue(userId, companyId)) {
            throw new AccessDeniedException("No tiene acceso a esta empresa");
        }
    }

    /** Extrae el userId del principal JWT autenticado, o null si es autenticación por API Key. */
    private Long getAuthenticatedUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
