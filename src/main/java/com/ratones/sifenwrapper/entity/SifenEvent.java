package com.ratones.sifenwrapper.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "sifen_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SifenEvent {

    public static final String ENVIADO = "ENVIADO";
    public static final String APROBADO = "APROBADO";
    public static final String RECHAZADO = "RECHAZADO";
    public static final String ERROR = "ERROR";
    public static final String ERROR_CONEXION = "ERROR_CONEXION";
    public static final String INDETERMINADO = "INDETERMINADO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "electronic_document_id")
    private Long electronicDocumentId;

    @Column(name = "tipo_evento", nullable = false)
    private Short tipoEvento;

    @Column(name = "evento_id", nullable = false, length = 10)
    private String eventoId;

    @Column(length = 44)
    private String cdc;

    @Column(columnDefinition = "TEXT")
    private String motivo;

    private Integer timbrado;

    @Column(length = 3)
    private String establecimiento;

    @Column(name = "punto_expedicion", length = 3)
    private String puntoExpedicion;

    @Column(name = "numero_desde", length = 7)
    private String numeroDesde;

    @Column(name = "numero_hasta", length = 7)
    private String numeroHasta;

    @Column(name = "tipo_documento")
    private Short tipoDocumento;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String estado = ENVIADO;

    @Column(name = "sifen_codigo", length = 10)
    private String sifenCodigo;

    @Column(name = "sifen_mensaje", columnDefinition = "TEXT")
    private String sifenMensaje;

    @Column(name = "protocolo_autorizacion", length = 30)
    private String protocoloAutorizacion;

    @Column(name = "fecha_proceso")
    private LocalDateTime fechaProceso;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_data", columnDefinition = "jsonb")
    private String requestData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_data", columnDefinition = "jsonb")
    private String responseData;

    @Column(name = "xml_enviado", columnDefinition = "TEXT")
    private String xmlEnviado;

    @Column(name = "xml_respuesta", columnDefinition = "TEXT")
    private String xmlRespuesta;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
