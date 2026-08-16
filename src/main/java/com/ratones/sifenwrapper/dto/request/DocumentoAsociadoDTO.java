package com.ratones.sifenwrapper.dto.request;

import lombok.Data;

/**
 * Grupo H (gCamDEAsoc) del Manual Técnico SIFEN: documento que una Nota de
 * Crédito/Débito ajusta. Obligatorio y único para tipoDocumento 5/6 (validación
 * SIFEN 2415). tipoDocumentoAsociado=1 usa solo cdcAsociado; =2 usa los campos
 * del comprobante impreso (timbradoAsociado..fechaEmisionAsociado).
 */
@Data
public class DocumentoAsociadoDTO {
    /** H002 iTipDocAso: 1=documento electrónico (por CDC), 2=documento impreso. */
    private Integer tipoDocumentoAsociado;

    // ── tipoDocumentoAsociado = 1 ──
    /** H004 dCdCDERef: CDC de 44 dígitos de la Factura/Autofactura asociada. */
    private String cdcAsociado;

    // ── tipoDocumentoAsociado = 2 ──
    /** H005 dNTimDI: número de timbrado del comprobante impreso (8 dígitos). */
    private String timbradoAsociado;
    /** H006 dEstDocAso. */
    private String establecimientoAsociado;
    /** H007 dPExpDocAso. */
    private String puntoAsociado;
    /** H008 dNumDocAso. */
    private String numeroAsociado;
    /** H009 iTipoDocAso: 1=Factura, 2=Nota de crédito, 3=Nota de débito, 4=Nota de remisión, 5=Comprobante de retención. */
    private Integer tipoComprobanteAsociado;
    /** H011 dFecEmiDI, formato yyyy-MM-dd. */
    private String fechaEmisionAsociado;
}
