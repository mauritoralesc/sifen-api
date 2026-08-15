package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResendResultDTO {
    private String cdc;
    private boolean reenviado;
    private String estadoAnterior;
    private String detalle;
}
