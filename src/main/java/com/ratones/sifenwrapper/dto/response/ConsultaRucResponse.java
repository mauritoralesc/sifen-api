package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ConsultaRucResponse {
    private String ruc;
    private String razonSocial;
    private String estado;
    private String codigoEstado;
    private String descripcionEstado;
    private List<MensajeSifenDTO> mensajes;
    private RespuestaSifenDTO respuestaSifen;
}
