package com.ratones.sifenwrapper.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request para enviar un evento SIFEN sobre un Documento Electrónico.
 *
 * Tipos de evento soportados:
 *   1 = Cancelación (requiere: cdc, motivo)
 *   2 = Inutilización (requiere: timbrado, establecimiento, puntoExpedicion, numeroDesde, numeroHasta, tipoDocumento, motivo)
 *   3 = Conformidad del receptor (requiere: cdc, tipoConformidad, fechaRecepcion)
 *   4 = Disconformidad del receptor (requiere: cdc, motivo)
 *   5 = Desconocimiento del receptor (requiere: cdc, motivo + datos receptor)
 *   6 = Notificación de recepción (requiere: cdc + datos receptor + totalGs)
 *
 * Las anotaciones de esta clase validan solo formato (capa 1); la obligatoriedad
 * condicional por tipo y las reglas de negocio (plazos, pertenencia del CDC al
 * tenant, etc.) se validan en {@link com.ratones.sifenwrapper.service.EventoValidator}.
 */
@Data
public class EventoRequest {

    /** Tipo de evento (1-6). Ver tabla en Javadoc. */
    @Min(value = 1, message = "tipoEvento es obligatorio y debe estar entre 1 y 6")
    @Max(value = 6, message = "tipoEvento debe estar entre 1 y 6")
    private int tipoEvento;

    /** CDC del documento electrónico (44 dígitos). Obligatorio para eventos 1, 3, 4, 5, 6. */
    @Pattern(regexp = "\\d{44}", message = "El CDC debe tener exactamente 44 dígitos")
    private String cdc;

    /** Motivo o descripción del evento. Obligatorio (mínimo 15 caracteres) para cancelación, inutilización, disconformidad, desconocimiento. */
    @Size(min = 15, max = 500, message = "motivo debe tener entre 15 y 500 caracteres (regla SIFEN)")
    private String motivo;

    // ─── Campos para Inutilización (tipo 2) ──────────────────────────────────

    /** Número de timbrado */
    @Min(value = 1, message = "timbrado debe ser un número positivo")
    @Max(value = 99_999_999, message = "timbrado no puede superar 8 dígitos")
    private Integer timbrado;

    /** Código de establecimiento (ej: "001") */
    @Pattern(regexp = "\\d{3}", message = "establecimiento debe tener exactamente 3 dígitos")
    private String establecimiento;

    /** Punto de expedición (ej: "001") */
    @Pattern(regexp = "\\d{3}", message = "puntoExpedicion debe tener exactamente 3 dígitos")
    private String puntoExpedicion;

    /** Número de documento desde (ej: "0000001") */
    @Pattern(regexp = "\\d{1,7}", message = "numeroDesde debe ser numérico de hasta 7 dígitos")
    private String numeroDesde;

    /** Número de documento hasta (ej: "0000010") */
    @Pattern(regexp = "\\d{1,7}", message = "numeroHasta debe ser numérico de hasta 7 dígitos")
    private String numeroHasta;

    /** Tipo de documento electrónico (1=FE, 2=FE exportación, 3=FE importación, 4=AFE, 5=NCE, 6=NDE, 7=NRE, 8=comprobante retención) */
    @Min(value = 1, message = "tipoDocumento es obligatorio")
    @Max(value = 8, message = "tipoDocumento inválido")
    private Integer tipoDocumento;

    // ─── Campos para Conformidad (tipo 3) ─────────────────────────────────────

    /** Tipo de conformidad: 1 = Total, 2 = Parcial */
    @Min(value = 1, message = "tipoConformidad debe ser 1 (Total) o 2 (Parcial)")
    @Max(value = 2, message = "tipoConformidad debe ser 1 (Total) o 2 (Parcial)")
    private Integer tipoConformidad;

    /** Fecha de recepción del documento (ISO format: "2026-03-04T10:00:00") */
    private String fechaRecepcion;

    // ─── Campos para Desconocimiento (tipo 5) y Notificación (tipo 6) ────────

    /** Fecha de emisión del DE (ISO format) */
    private String fechaEmision;

    /** Tipo de receptor: CONTRIBUYENTE / NO_CONTRIBUYENTE */
    private Boolean receptorContribuyente;

    /** Nombre del receptor */
    @Size(max = 250, message = "nombreReceptor no puede superar 250 caracteres")
    private String nombreReceptor;

    /** RUC del receptor (sin DV). Si se omite en tipos 5/6, se autocompleta con el RUC del tenant autenticado. */
    @Pattern(regexp = "\\d{1,8}", message = "rucReceptor debe ser numérico de hasta 8 dígitos")
    private String rucReceptor;

    /** Dígito verificador del RUC receptor */
    @Pattern(regexp = "\\d", message = "dvReceptor debe ser un único dígito")
    private String dvReceptor;

    /** Tipo de documento de identidad del receptor (1=CI, 2=Pasaporte, 3=Cédula extranjera, 4=Carnet residencia, 5=Innominado, 6=Tarjeta diplomática, 9=Otro) */
    @Min(value = 1, message = "tipoDocIdentidad inválido")
    @Max(value = 9, message = "tipoDocIdentidad inválido")
    private Integer tipoDocIdentidad;

    /** Número de documento de identidad */
    @Size(max = 20, message = "numeroDocIdentidad no puede superar 20 caracteres")
    private String numeroDocIdentidad;

    /** Total en guaraníes (solo para Notificación tipo 6) */
    @DecimalMin(value = "0", inclusive = false, message = "totalGs debe ser mayor a cero")
    private BigDecimal totalGs;
}
