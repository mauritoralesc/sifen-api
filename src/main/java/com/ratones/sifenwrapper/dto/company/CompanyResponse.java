package com.ratones.sifenwrapper.dto.company;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CompanyResponse {
    private Long id;
    private String nombre;
    private String ruc;
    private String dv;
    private String ambiente;
    private String cscId;
    private boolean hasCertificado;
    private boolean hasEmisorConfig;
    private boolean habilitarNt13;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
