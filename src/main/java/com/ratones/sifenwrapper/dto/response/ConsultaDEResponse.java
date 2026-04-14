package com.ratones.sifenwrapper.dto.response;

import com.ratones.sifenwrapper.dto.response.consulta.ConsultaDocumentoDTO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ConsultaDEResponse {
    private String cdc;
    private String estado;
    private String codigoEstado;
    private String descripcionEstado;
    private String qrUrl;
    private String protocoloAutorizacion;
    private LocalDateTime fechaProcesamiento;
    private ConsultaDocumentoDTO documento;
    private List<MensajeSifenDTO> mensajes;
    private RespuestaSifenDTO respuestaSifen;
}
