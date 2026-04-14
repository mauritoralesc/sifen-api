package com.ratones.sifenwrapper.controller;

import com.ratones.sifenwrapper.dto.company.*;
import com.ratones.sifenwrapper.dto.request.ParamsDTO;
import com.ratones.sifenwrapper.dto.response.SifenApiResponse;
import com.ratones.sifenwrapper.service.CertificateService;
import com.ratones.sifenwrapper.service.CompanyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @PostMapping
    public ResponseEntity<SifenApiResponse<CompanyResponse>> create(@RequestBody CreateCompanyRequest request) {
        log.info("POST /companies - nombre={}, ruc={}", request.getNombre(), request.getRuc());
        CompanyResponse response = companyService.create(request);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Empresa creada correctamente"));
    }

    @GetMapping
    public ResponseEntity<SifenApiResponse<List<CompanyResponse>>> findAll() {
        log.info("GET /companies");
        return ResponseEntity.ok(SifenApiResponse.ok(companyService.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> findById(@PathVariable Long id) {
        log.info("GET /companies/{}", id);
        return ResponseEntity.ok(SifenApiResponse.ok(companyService.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> update(
            @PathVariable Long id, @RequestBody UpdateCompanyRequest request) {
        log.info("PUT /companies/{}", id);
        return ResponseEntity.ok(SifenApiResponse.ok(companyService.update(id, request), "Empresa actualizada"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SifenApiResponse<Void>> deactivate(@PathVariable Long id) {
        log.info("DELETE /companies/{}", id);
        companyService.deactivate(id);
        return ResponseEntity.ok(SifenApiResponse.ok(null, "Empresa desactivada"));
    }

    @PostMapping(value = "/{id}/certificate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SifenApiResponse<CompanyResponse>> uploadCertificate(
            @PathVariable Long id,
            @RequestParam("certificate") MultipartFile file,
            @RequestParam("password") String password) throws IOException {
        log.info("POST /companies/{}/certificate - filename={}", id, file.getOriginalFilename());

        byte[] pfxBytes = file.getBytes();
        certificateService.validate(pfxBytes, password);
        CompanyResponse response = companyService.uploadCertificate(id, pfxBytes, password);
        return ResponseEntity.ok(SifenApiResponse.ok(response, "Certificado cargado correctamente"));
    }

    @DeleteMapping("/{id}/certificate")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> deleteCertificate(@PathVariable Long id) {
        log.info("DELETE /companies/{}/certificate", id);
        return ResponseEntity.ok(SifenApiResponse.ok(companyService.deleteCertificate(id), "Certificado eliminado"));
    }

    @PutMapping("/{id}/csc")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> updateCsc(
            @PathVariable Long id, @RequestBody UpdateCscRequest request) {
        log.info("PUT /companies/{}/csc", id);
        return ResponseEntity.ok(SifenApiResponse.ok(companyService.updateCsc(id, request), "CSC actualizado"));
    }

    @PutMapping("/{id}/emisor")
    public ResponseEntity<SifenApiResponse<CompanyResponse>> updateEmisorConfig(
            @PathVariable Long id, @RequestBody ParamsDTO emisorConfig) {
        log.info("PUT /companies/{}/emisor", id);
        return ResponseEntity.ok(SifenApiResponse.ok(
                companyService.updateEmisorConfig(id, emisorConfig),
                "Configuración de emisor actualizada"));
    }

    @GetMapping("/{id}/emisor")
    public ResponseEntity<SifenApiResponse<ParamsDTO>> getEmisorConfig(@PathVariable Long id) {
        log.info("GET /companies/{}/emisor", id);
        ParamsDTO config = companyService.getEmisorConfig(id);
        if (config == null) {
            return ResponseEntity.ok(SifenApiResponse.ok(null, "La empresa no tiene configuración de emisor"));
        }
        return ResponseEntity.ok(SifenApiResponse.ok(config));
    }
}
