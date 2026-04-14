package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResultadoLoteItemDTO {
    private String cdc;
    private String estado;
    private String descripcion;
}
