package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class DocumentStatusResponse {
    private String cdc;
    private String estado;
    private String codigoEstado;
    private String descripcionEstado;
    private String nroLote;
    private String qrUrl;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private LocalDateTime processedAt;
    /** Todos los mensajes SIFEN del resultado (rechazo o aprobación con observación). */
    private List<MensajeSifenDTO> mensajes;
    /** true si el documento puede reenviarse con POST /invoices/{cdc}/resend reutilizando el mismo CDC. */
    private Boolean reenviable;
    /** AUTOMATICO | REQUIERE_CORRECCION | NO_REENVIABLE — ver SifenRechazoClassifier. */
    private String clasificacionReenvio;
    /** Texto accionable para el ERP sobre qué hacer con este documento. */
    private String accionSugerida;
    /** true si existe un evento de cancelación (tipo 1) APROBADO para este CDC. */
    private Boolean cancelado;
    /** Último evento registrado para este CDC (cualquier tipo/estado), o null si no hay ninguno. */
    private EventoResumenDTO ultimoEvento;
}
