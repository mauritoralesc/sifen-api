package com.ratones.sifenwrapper.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
