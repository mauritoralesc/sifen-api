package com.ratones.sifenwrapper.dto.response.consulta;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ConsultaCondicionDTO {
    private Integer tipo;
    private String tipoDescripcion;
    private List<ConsultaEntregaDTO> entregas;
}
