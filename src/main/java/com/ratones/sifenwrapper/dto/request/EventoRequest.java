package com.ratones.sifenwrapper.dto.request;

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
 */
@Data
public class EventoRequest {

    /** Tipo de evento (1-6). Ver tabla en Javadoc. */
    private int tipoEvento;

    /** CDC del documento electrónico (44 caracteres). Obligatorio para eventos 1, 3, 4, 5, 6. */
    private String cdc;

    /** Motivo o descripción del evento. Obligatorio para cancelación, disconformidad, desconocimiento. */
    private String motivo;

    // ─── Campos para Inutilización (tipo 2) ──────────────────────────────────

    /** Número de timbrado */
    private Integer timbrado;

    /** Código de establecimiento (ej: "001") */
    private String establecimiento;

    /** Punto de expedición (ej: "001") */
    private String puntoExpedicion;

    /** Número de documento desde (ej: "0000001") */
    private String numeroDesde;

    /** Número de documento hasta (ej: "0000010") */
    private String numeroHasta;

    /** Tipo de documento electrónico (1=FE, 4=AFE, 5=NCE, 6=NDE, 7=NRE) */
    private Integer tipoDocumento;

    // ─── Campos para Conformidad (tipo 3) ─────────────────────────────────────

    /** Tipo de conformidad: 1 = Total, 2 = Parcial */
    private Integer tipoConformidad;

    /** Fecha de recepción del documento (ISO format: "2026-03-04T10:00:00") */
    private String fechaRecepcion;

    // ─── Campos para Desconocimiento (tipo 5) y Notificación (tipo 6) ────────

    /** Fecha de emisión del DE (ISO format) */
    private String fechaEmision;

    /** Tipo de receptor: CONTRIBUYENTE / NO_CONTRIBUYENTE */
    private Boolean receptorContribuyente;

    /** Nombre del receptor */
    private String nombreReceptor;

    /** RUC del receptor (sin DV) */
    private String rucReceptor;

    /** Dígito verificador del RUC receptor */
    private String dvReceptor;

    /** Tipo de documento de identidad del receptor (1=CI, 2=Pasaporte, 3=ID extranjero, 4=Carnet residencia) */
    private Integer tipoDocIdentidad;

    /** Número de documento de identidad */
    private String numeroDocIdentidad;

    /** Total en guaraníes (solo para Notificación tipo 6) */
    private BigDecimal totalGs;
}
