package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class NotaCreditoResumenDTO {
    private String cdc;
    private String establecimiento;
    private String punto;
    private String numero;
    private String estado;
    private Short motivoEmision;
    private String motivoDescripcion;
    private String moneda;
    private BigDecimal montoTotal;
    private String sifenCodigo;
    private String sifenMensaje;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}
