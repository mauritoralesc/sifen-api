package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PrepareInvoiceResponse {
    private String cdc;
    private String qrUrl;
    private String estado;
    private String numero;
    private String establecimiento;
    private String punto;
    private String xmlFirmado;
    private String kude;
}
