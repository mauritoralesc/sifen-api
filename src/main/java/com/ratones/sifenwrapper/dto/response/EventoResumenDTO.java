package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resumen de un evento SIFEN persistido, para los endpoints de consulta
 * (GET /invoices/{cdc}/events, GET /invoices/events) y para enriquecer
 * DocumentStatusResponse. Deliberadamente excluye xmlEnviado/xmlRespuesta —
 * son blobs firmados de varios KB; quedan en BD para forense y se exponen
 * solo en la respuesta de un evento individual (RecepcionEventoResponse).
 */
@Data
@Builder
public class EventoResumenDTO {
    private Long id;
    private String eventoId;
    private Short tipoEvento;
    private String tipoEventoDescripcion;
    private String cdc;
    private String estado;
    private String sifenCodigo;
    private String sifenMensaje;
    private String protocoloAutorizacion;
    private String motivo;
    private Integer timbrado;
    private String establecimiento;
    private String puntoExpedicion;
    private String numeroDesde;
    private String numeroHasta;
    private Short tipoDocumento;
    private List<MensajeSifenDTO> mensajes;
    private String clasificacion;
    private Boolean reintentable;
    private String accionSugerida;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private LocalDateTime processedAt;
}
