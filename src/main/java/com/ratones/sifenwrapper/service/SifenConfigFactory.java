package com.ratones.sifenwrapper.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratones.sifenwrapper.entity.Company;
import com.ratones.sifenwrapper.repository.CompanyRepository;
import com.ratones.sifenwrapper.security.TenantContext;
import com.roshka.sifen.Sifen;
import com.roshka.sifen.core.SifenConfig;
import com.roshka.sifen.core.exceptions.SifenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class SifenConfigFactory {

    private final CompanyRepository companyRepository;
    private final EncryptionService encryptionService;

    private final Cache<Long, SifenConfig> configCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    /**
     * Lock global que serializa todas las operaciones que involucren Sifen.setSifenConfig().
     * La librería rshk-jsifenlib mantiene el config en un campo estático compartido,
     * por lo que no es thread-safe en entornos multi-tenant concurrentes.
     */
    private static final ReentrantLock SIFEN_GLOBAL_LOCK = new ReentrantLock();

    /**
     * Interfaz funcional para operaciones SIFEN que pueden lanzar SifenException.
     */
    @FunctionalInterface
    public interface SifenCallable<T> {
        T call() throws Exception;
    }

    /**
     * Establece el config de SIFEN y ejecuta la operación dentro de un lock global.
     * Garantiza que no haya race conditions entre empresas con diferente config
     * (ej. PROD vs DEV con el mismo RUC).
     */
    public <T> T withSifenConfig(SifenConfig config, SifenCallable<T> operation) throws SifenException {
        SIFEN_GLOBAL_LOCK.lock();
        try {
            Sifen.setSifenConfig(config);
            return operation.call();
        } catch (SifenException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error en operación SIFEN", e);
        } finally {
            SIFEN_GLOBAL_LOCK.unlock();
        }
    }

    public SifenConfigFactory(CompanyRepository companyRepository, EncryptionService encryptionService) {
        this.companyRepository = companyRepository;
        this.encryptionService = encryptionService;
    }

    /**
     * Obtiene el SifenConfig para la empresa del tenant actual.
     * Usa cache para evitar reconstruir en cada request.
     */
    public SifenConfig getConfigForCurrentTenant() {
        Long companyId = TenantContext.get();
        if (companyId == null) {
            throw new IllegalStateException("No hay tenant configurado para la request actual");
        }
        return getConfigForCompany(companyId);
    }

    public SifenConfig getConfigForCompany(Long companyId) {
        SifenConfig cached = configCache.getIfPresent(companyId);
        if (cached != null) {
            return cached;
        }

        Company company = companyRepository.findByIdAndActiveTrue(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Empresa no encontrada o inactiva: " + companyId));

        SifenConfig config = buildConfig(company);
        configCache.put(companyId, config);
        return config;
    }

    /**
     * Invalida el cache de una empresa (llamar al actualizar cert/csc/ambiente).
     */
    public void evict(Long companyId) {
        configCache.invalidate(companyId);
    }

    public void evictAll() {
        configCache.invalidateAll();
    }

    private SifenConfig buildConfig(Company company) {
        SifenConfig config = new SifenConfig();

            // Ambiente
            SifenConfig.TipoAmbiente ambiente = "PROD".equalsIgnoreCase(company.getAmbiente())
                    ? SifenConfig.TipoAmbiente.PROD
                    : SifenConfig.TipoAmbiente.DEV;
            config.setAmbiente(ambiente);

            // Certificado
            if (company.getCertificadoPfx() != null) {
                File tempPfx = writeTempPfx(company.getCertificadoPfx(), company.getId());
                config.setUsarCertificadoCliente(true);
                config.setTipoCertificadoCliente(SifenConfig.TipoCertificadoCliente.PFX);
                config.setCertificadoCliente(tempPfx.getAbsolutePath());
                config.setContrasenaCertificadoCliente(
                        encryptionService.decrypt(company.getCertificadoPassword()));
            }

            // CSC
            if (company.getCscId() != null && company.getCscValor() != null) {
                config.setIdCSC(company.getCscId());
                config.setCSC(encryptionService.decrypt(company.getCscValor()));
            }

            // Nota Técnica 13
            config.setHabilitarNotaTecnica13(company.isHabilitarNt13());

            log.debug("SifenConfig construido para empresa id={} ambiente={}", company.getId(), ambiente);
            return config;
    }

    private File writeTempPfx(byte[] pfxBytes, Long companyId) {
        try {
            File tempDir = Files.createTempDirectory("sifen-certs").toFile();
            tempDir.deleteOnExit();
            File tempFile = new File(tempDir, "company_" + companyId + ".pfx");
            tempFile.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(pfxBytes);
            }
            return tempFile;
        } catch (IOException e) {
            throw new RuntimeException("Error escribiendo certificado temporal", e);
        }
    }
}
