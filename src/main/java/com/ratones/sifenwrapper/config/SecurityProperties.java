package com.ratones.sifenwrapper.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    private Jwt jwt = new Jwt();
    private Encryption encryption = new Encryption();

    @Data
    public static class Jwt {
        private String secret;
        private long accessTokenExpiration = 3600;
        private long refreshTokenExpiration = 604800;
    }

    @Data
    public static class Encryption {
        private String key;
    }
}
