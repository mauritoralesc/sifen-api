package com.ratones.sifenwrapper.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración SIFEN.
 * En modo SaaS multi-tenant, la configuración se construye dinámicamente por empresa
 * mediante SifenConfigFactory. Esta clase se mantiene como placeholder de configuración.
 * La configuración global del YAML (SifenProperties) ya no se usa directamente.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SifenConfiguration {

    // La configuración SIFEN ahora es dinámica por tenant.
    // Ver SifenConfigFactory para la construcción de SifenConfig por empresa.
}
