package com.ratones.sifenwrapper.dto.response.consulta;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConsultaActividadEconomicaDTO {
    private String codigo;
    private String descripcion;
}
