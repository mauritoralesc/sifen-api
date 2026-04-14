package com.ratones.sifenwrapper.dto.request;

import lombok.Data;
import java.util.List;

@Data
public class CreditoDTO {
    private int tipo;
    private int plazo;
    private int cuotas;
    private List<CuotaDTO> infoCuotas;
}
