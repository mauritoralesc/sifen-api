package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentStatusResponse {
    private String cdc;
    private String estado;
    private String codigoEstado;
    private String descripcionEstado;
    private String nroLote;
    private String qrUrl;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private LocalDateTime processedAt;
}
