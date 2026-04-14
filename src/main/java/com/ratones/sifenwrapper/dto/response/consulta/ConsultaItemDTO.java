package com.ratones.sifenwrapper.dto.response.consulta;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ConsultaItemDTO {
    private String codigo;
    private String descripcion;
    private Integer unidadMedida;
    private String unidadMedidaDescripcion;
    private BigDecimal cantidad;
    private BigDecimal precioUnitario;
    private BigDecimal totalBruto;
    private Integer afectacionIva;
    private String afectacionIvaDescripcion;
    private BigDecimal proporcionIva;
    private BigDecimal tasaIva;
    private BigDecimal baseGravadaIva;
    private BigDecimal liquidacionIva;
    private BigDecimal baseExenta;
}
