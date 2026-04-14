package com.ratones.sifenwrapper.dto.response.consulta;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ConsultaTotalesDTO {
    private BigDecimal subtotalExenta;
    private BigDecimal subtotalExonerada;
    private BigDecimal subtotalIva5;
    private BigDecimal subtotalIva10;
    private BigDecimal totalOperacion;
    private BigDecimal totalDescuento;
    private BigDecimal totalAnticipo;
    private BigDecimal redondeo;
    private BigDecimal totalGeneral;
    private BigDecimal liquidacionIva5;
    private BigDecimal liquidacionIva10;
    private BigDecimal totalIva;
    private BigDecimal baseGravada5;
    private BigDecimal baseGravada10;
    private BigDecimal totalBaseGravada;
    private BigDecimal totalGuaranies;
}
