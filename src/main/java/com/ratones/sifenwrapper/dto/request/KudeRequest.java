package com.ratones.sifenwrapper.dto.request;

import lombok.Data;

/**
 * Request para generar un KUDE (PDF) de un Documento Electrónico ya emitido.
 * Contiene los mismos datos del DE original más los datos de la respuesta SIFEN.
 */
@Data
public class KudeRequest {
    /** Datos del emisor y timbrado (mismos que en la emisión) */
    private ParamsDTO params;

    /** Datos del documento (mismos que en la emisión) */
    private DataDTO data;

    /** CDC del documento electrónico aprobado (44 caracteres) */
    private String cdc;

    /** URL del QR del documento */
    private String qrUrl;

    /** Estado SIFEN: APROBADO, RECHAZADO, etc. */
    private String estado;

    /** Código de respuesta SIFEN (ej: "0260") */
    private String codigoEstado;

    /** Descripción de la respuesta SIFEN */
    private String descripcionEstado;
}
