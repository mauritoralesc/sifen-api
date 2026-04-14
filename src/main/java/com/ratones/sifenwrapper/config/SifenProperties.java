package com.ratones.sifenwrapper.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sifen")
public class SifenProperties {

    private String ambiente = "DEV";
    private Certificado certificado = new Certificado();
    private Csc csc = new Csc();
    private boolean habilitarNotaTecnica13 = true;

    @Data
    public static class Certificado {
        private boolean usar = true;
        private String tipo = "PFX";
        private String archivo;
        private String contrasena;
    }

    @Data
    public static class Csc {
        private String id;
        private String valor;
    }
}
