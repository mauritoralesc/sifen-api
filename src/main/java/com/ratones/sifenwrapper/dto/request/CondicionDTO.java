package com.ratones.sifenwrapper.dto.request;

import lombok.Data;
import java.util.List;

@Data
public class CondicionDTO {
    private int tipo;
    private List<EntregaDTO> entregas;
    private CreditoDTO credito;
}
