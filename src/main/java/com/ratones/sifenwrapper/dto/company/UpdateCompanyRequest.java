package com.ratones.sifenwrapper.dto.company;

import lombok.Data;

@Data
public class UpdateCompanyRequest {
    private String nombre;
    private String ruc;
    private String dv;
    private String ambiente;
    private boolean habilitarNt13;
}
