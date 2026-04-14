package com.ratones.sifenwrapper.dto.company;

import lombok.Data;

@Data
public class CreateCompanyRequest {
    private String nombre;
    private String ruc;
    private String dv;
    private String ambiente;
    private String cscId;
    private String cscValor;
    private boolean habilitarNt13 = true;
}
