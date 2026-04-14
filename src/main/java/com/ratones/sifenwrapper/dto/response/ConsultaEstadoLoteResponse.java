package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ConsultaEstadoLoteResponse {
    private String nroLote;
    private String estado;
    private String codigoEstado;
    private String descripcionEstado;
    private List<ResultadoLoteItemDTO> resultados;
    private List<MensajeSifenDTO> mensajes;
    private RespuestaSifenDTO respuestaSifen;
}
