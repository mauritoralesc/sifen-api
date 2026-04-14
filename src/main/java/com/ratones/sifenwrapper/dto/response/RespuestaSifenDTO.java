package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RespuestaSifenDTO {
    private int codigoRespuesta;
    private String descripcionRespuesta;
    private String xmlRespuesta;
    /** XML SOAP enviado a SIFEN (solo incluido para depuración de eventos). */
    private String xmlEnviado;
}
