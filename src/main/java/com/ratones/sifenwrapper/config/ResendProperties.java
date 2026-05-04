package com.ratones.sifenwrapper.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "resend")
public class ResendProperties {

    /** API key de Resend (ej: re_xxx). */
    private String apiKey;

    /** Correo remitente verificado en Resend. */
    private String fromEmail = "no-reply@synctema.com";

    /** Nombre visible del remitente. */
    private String fromName = "SYNCTEMA";
}
