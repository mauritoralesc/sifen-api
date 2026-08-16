package com.ratones.sifenwrapper.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "electronic_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ElectronicDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, unique = true, length = 44)
    private String cdc;

    @Column(name = "tipo_documento", nullable = false)
    private Short tipoDocumento;

    @Column(nullable = false, length = 7)
    private String numero;

    @Column(nullable = false, length = 3)
    private String establecimiento;

    @Column(nullable = false, length = 3)
    private String punto;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String estado = "PREPARADO";

    @Column(name = "xml_firmado", nullable = false, columnDefinition = "TEXT")
    private String xmlFirmado;

    @Column(name = "qr_url", length = 512)
    private String qrUrl;

    @Column(name = "nro_lote", length = 30)
    private String nroLote;

    @Column(name = "sifen_codigo", length = 10)
    private String sifenCodigo;

    @Column(name = "sifen_mensaje", columnDefinition = "TEXT")
    private String sifenMensaje;

    /** CDC del documento que esta NC/ND ajusta (grupo H, solo asociación electrónica). */
    @Column(name = "cdc_asociado", length = 44)
    private String cdcAsociado;

    @Column(length = 3)
    private String moneda;

    /** F014 dTotGralOpe, leído del DE luego de generarXml. Null en filas legacy (pre-V17). */
    @Column(name = "monto_total", precision = 23, scale = 8)
    private BigDecimal montoTotal;

    /** E401 iMotEmi (solo NC/ND). */
    @Column(name = "motivo_emision")
    private Short motivoEmision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_data", columnDefinition = "jsonb")
    private String requestData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_data", columnDefinition = "jsonb")
    private String responseData;

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
