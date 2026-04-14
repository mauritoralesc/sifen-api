package com.ratones.sifenwrapper.dto.request;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class EntregaDTO {
    private int tipo;
    private BigDecimal monto;
    private String moneda;
}
