package com.ratones.sifenwrapper.dto.company;

import lombok.Data;

@Data
public class UpdateCompanyRequest {
    private String nombre;
    private String ambiente;
    private boolean habilitarNt13;
}
