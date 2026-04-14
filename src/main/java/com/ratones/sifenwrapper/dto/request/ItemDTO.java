package com.ratones.sifenwrapper.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ItemDTO {
    private String codigo;
    private String descripcion;
    private BigDecimal cantidad;
    private BigDecimal precioUnitario;
    private int ivaTipo;
    private BigDecimal ivaProporcion;
    private BigDecimal iva;
    private int unidadMedida;
    private String lote;
    private String vencimiento;
    private String numeroSerie;
    private BigDecimal descuento;
    private BigDecimal anticipo;
    private String numeroPedido;
    private String numeroSeguimiento;
    private List<SectorAduaneroDTO> sectoresAduaneros;
}
