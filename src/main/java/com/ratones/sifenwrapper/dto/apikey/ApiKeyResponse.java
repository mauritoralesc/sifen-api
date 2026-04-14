package com.ratones.sifenwrapper.dto.apikey;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ApiKeyResponse {
    private Long id;
    private String keyPrefix;
    private String name;
    private boolean active;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    /** Solo se incluye al momento de la creación, luego es null */
    private String rawKey;
}
