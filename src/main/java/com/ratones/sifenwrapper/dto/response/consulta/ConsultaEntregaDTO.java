package com.ratones.sifenwrapper.dto.response.consulta;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ConsultaEntregaDTO {
    private Integer tipoPago;
    private String tipoPagoDescripcion;
    private BigDecimal monto;
    private String moneda;
}
