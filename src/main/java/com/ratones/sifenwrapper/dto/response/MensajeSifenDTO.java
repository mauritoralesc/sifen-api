package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MensajeSifenDTO {
    private String codigo;
    private String descripcion;
}
