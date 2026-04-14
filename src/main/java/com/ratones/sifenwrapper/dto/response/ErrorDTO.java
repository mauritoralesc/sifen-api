package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ErrorDTO {
    private String codigo;
    private String descripcion;
}
