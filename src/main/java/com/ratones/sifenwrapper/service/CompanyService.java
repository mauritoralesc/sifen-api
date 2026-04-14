package com.ratones.sifenwrapper.service;

import com.ratones.sifenwrapper.dto.company.*;
import com.ratones.sifenwrapper.dto.request.ParamsDTO;
import com.ratones.sifenwrapper.entity.Company;
import com.ratones.sifenwrapper.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyService {

    private static final String DEFAULT_AMBIENTE = "DEV";

    private final CompanyRepository companyRepository;
    private final EncryptionService encryptionService;
    private final SifenConfigFactory sifenConfigFactory;

    @Transactional
    @SuppressWarnings("null")
    public CompanyResponse create(CreateCompanyRequest request) {
        String ambiente = normalizeAmbiente(request.getAmbiente(), DEFAULT_AMBIENTE);
        String nombre = normalizeNombre(request.getNombre());

        Company company = Company.builder()
            .nombre(nombre)
                .ruc(request.getRuc())
                .dv(request.getDv())
                .ambiente(ambiente)
                .cscId(request.getCscId())
                .cscValor(request.getCscValor() != null ? encryptionService.encrypt(request.getCscValor()) : null)
                .habilitarNt13(request.isHabilitarNt13())
                .build();

        Company savedCompany = companyRepository.save(company);
        log.info("Empresa creada: {} (RUC: {})", savedCompany.getNombre(), savedCompany.getRuc());
        return toResponse(savedCompany);
    }

    public List<CompanyResponse> findAll() {
        return companyRepository.findAllByActiveTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    public CompanyResponse findById(Long id) {
        Company company = getActiveCompanyOrThrow(id);
        return toResponse(company);
    }

    @Transactional
    public CompanyResponse update(Long id, UpdateCompanyRequest request) {
        Company company = getActiveCompanyOrThrow(id);

        String nombre = request.getNombre() != null ? normalizeNombre(request.getNombre()) : company.getNombre();
        String ambiente = normalizeAmbiente(request.getAmbiente(), company.getAmbiente());

        company.setNombre(nombre);
        company.setAmbiente(ambiente);
        company.setHabilitarNt13(request.isHabilitarNt13());
        company = companyRepository.save(company);
        sifenConfigFactory.evict(id);
        log.info("Empresa actualizada: id={}", id);
        return toResponse(company);
    }

    @Transactional
    public void deactivate(Long id) {
        Company company = getActiveCompanyOrThrow(id);
        company.setActive(false);
        companyRepository.save(company);
        log.info("Empresa desactivada: id={}", id);
    }

    @Transactional
    public CompanyResponse updateCsc(Long id, UpdateCscRequest request) {
        Company company = getActiveCompanyOrThrow(id);
        company.setCscId(request.getCscId());
        company.setCscValor(encryptionService.encrypt(request.getCscValor()));
        company = companyRepository.save(company);
        sifenConfigFactory.evict(id);
        log.info("CSC actualizado para empresa id={}", id);
        return toResponse(company);
    }

    @Transactional
    public CompanyResponse uploadCertificate(Long id, byte[] pfxBytes, String password) {
        Company company = getActiveCompanyOrThrow(id);
        company.setCertificadoPfx(pfxBytes);
        company.setCertificadoPassword(encryptionService.encrypt(password));
        company = companyRepository.save(company);
        sifenConfigFactory.evict(id);
        log.info("Certificado actualizado para empresa id={}", id);
        return toResponse(company);
    }

    @Transactional
    public CompanyResponse deleteCertificate(Long id) {
        Company company = getActiveCompanyOrThrow(id);
        company.setCertificadoPfx(null);
        company.setCertificadoPassword(null);
        company = companyRepository.save(company);
        sifenConfigFactory.evict(id);
        log.info("Certificado eliminado para empresa id={}", id);
        return toResponse(company);
    }

    @Transactional
    public CompanyResponse updateEmisorConfig(Long id, ParamsDTO emisorConfig) {
        Company company = getActiveCompanyOrThrow(id);
        company.setEmisorConfig(emisorConfig);
        company = companyRepository.save(company);
        log.info("Configuración de emisor actualizada para empresa id={}", id);
        return toResponse(company);
    }

    public ParamsDTO getEmisorConfig(Long id) {
        Company company = getActiveCompanyOrThrow(id);
        return company.getEmisorConfig();
    }

    public Company getActiveCompanyOrThrow(Long id) {
        return companyRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new IllegalArgumentException("Empresa no encontrada: " + id));
    }

    private String normalizeAmbiente(String ambiente, String fallback) {
        String resolved = (ambiente == null || ambiente.isBlank()) ? fallback : ambiente;
        return resolved == null ? DEFAULT_AMBIENTE : resolved.trim().toUpperCase();
    }

    private String normalizeNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("El nombre de la empresa es obligatorio");
        }
        return nombre.trim();
    }

    private CompanyResponse toResponse(Company c) {
        return CompanyResponse.builder()
                .id(c.getId())
                .nombre(c.getNombre())
                .ruc(c.getRuc())
                .dv(c.getDv())
                .ambiente(c.getAmbiente())
                .cscId(c.getCscId())
                .hasCertificado(c.getCertificadoPfx() != null)
                .hasEmisorConfig(c.getEmisorConfig() != null)
                .habilitarNt13(c.isHabilitarNt13())
                .active(c.isActive())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
