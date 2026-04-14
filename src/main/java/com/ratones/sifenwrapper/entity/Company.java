package com.ratones.sifenwrapper.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.ratones.sifenwrapper.dto.request.ParamsDTO;
import java.time.LocalDateTime;

@Entity
@Table(name = "companies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String nombre;

    @Column(nullable = false, length = 20)
    private String ruc;

    @Column(nullable = false, length = 2)
    private String dv;

    @Column(nullable = false, length = 4)
    @Builder.Default
    private String ambiente = "DEV";

    @Column(name = "certificado_pfx")
    private byte[] certificadoPfx;

    @Column(name = "certificado_password", length = 500)
    private String certificadoPassword;

    @Column(name = "csc_id", length = 10)
    private String cscId;

    @Column(name = "csc_valor", length = 500)
    private String cscValor;

    @Column(name = "habilitar_nt13", nullable = false)
    @Builder.Default
    private boolean habilitarNt13 = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "emisor_config", columnDefinition = "jsonb")
    private ParamsDTO emisorConfig;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
