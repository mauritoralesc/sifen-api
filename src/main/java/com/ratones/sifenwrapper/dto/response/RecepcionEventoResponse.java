package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class RecepcionEventoResponse {
    private String cdc;
    private String estado;
    private String codigoEstado;
    private String descripcionEstado;
    private List<MensajeSifenDTO> mensajes;
    private RespuestaSifenDTO respuestaSifen;

    /** Id local (fila en sifen_events) del evento registrado. */
    private Long id;
    /** rEve/@Id enviado a SIFEN (tdIdEve): numérico secuencial, referenciado por la firma XML. */
    private String eventoId;
    private Short tipoEvento;
    /** Protocolo de autorización devuelto por SIFEN al aprobar el evento. */
    private String protocoloAutorizacion;
    private LocalDateTime fechaProceso;
    /** Ver SifenEventoClassifier.ResultadoEvento. */
    private String clasificacion;
    private Boolean reintentable;
    private String accionSugerida;
}
