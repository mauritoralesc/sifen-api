package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuditLogDTO {
    private Long id;
    private Long companyId;
    private Long userId;
    private String action;
    private String resource;
    private String detail;
    private String ip;
    private LocalDateTime createdAt;
}
