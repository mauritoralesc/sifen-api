package com.ratones.sifenwrapper.dto.request;

import lombok.Data;

@Data
public class RemisionDTO {
    private int kms;
    private int fechaFactura;
    private String tipoResponsable;
}
