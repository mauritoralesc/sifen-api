package com.ratones.sifenwrapper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Enumeration;

@Slf4j
@Service
public class CertificateService {

    /**
     * Valida que el byte array sea un PFX válido y que el password sea correcto.
     * Lanza IllegalArgumentException si es inválido.
     */
    public void validate(byte[] pfxBytes, String password) {
        if (pfxBytes == null || pfxBytes.length == 0) {
            throw new IllegalArgumentException("El archivo del certificado está vacío");
        }
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pfxBytes), password.toCharArray());

            Enumeration<String> aliases = ks.aliases();
            if (!aliases.hasMoreElements()) {
                throw new IllegalArgumentException("El certificado PFX no contiene entradas");
            }

            // Verificar que al menos un certificado no esté expirado
            boolean hasValid = false;
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (ks.isCertificateEntry(alias) || ks.isKeyEntry(alias)) {
                    java.security.cert.Certificate cert = ks.getCertificate(alias);
                    if (cert instanceof X509Certificate x509) {
                        LocalDateTime notAfter = x509.getNotAfter().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime();
                        if (notAfter.isAfter(LocalDateTime.now())) {
                            hasValid = true;
                        } else {
                            log.warn("Certificado alias='{}' expirado el {}", alias, notAfter);
                        }
                    }
                }
            }

            if (!hasValid) {
                throw new IllegalArgumentException("Todos los certificados en el PFX están expirados");
            }

            log.info("Certificado PFX validado correctamente");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Certificado PFX inválido o password incorrecto: " + e.getMessage());
        }
    }
}
