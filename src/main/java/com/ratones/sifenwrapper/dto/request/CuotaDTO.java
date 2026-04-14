package com.ratones.sifenwrapper.dto.request;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class CuotaDTO {
    private int moneda;
    private BigDecimal monto;
    private String vencimiento;
}
