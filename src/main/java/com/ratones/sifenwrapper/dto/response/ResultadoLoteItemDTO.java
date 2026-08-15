package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResultadoLoteItemDTO {
    private String cdc;
    private String estado;
    private String descripcion;
    /** Código SIFEN del primer mensaje de procesamiento (dCodRes), si vino en la respuesta. */
    private String codigo;
    /** true si el documento puede reenviarse con POST /invoices/{cdc}/resend reutilizando el mismo CDC. */
    private Boolean reenviable;
    /** AUTOMATICO | REQUIERE_CORRECCION | NO_REENVIABLE — ver SifenRechazoClassifier. */
    private String clasificacionReenvio;
}
