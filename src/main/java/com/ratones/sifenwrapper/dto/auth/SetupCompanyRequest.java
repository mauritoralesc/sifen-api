package com.ratones.sifenwrapper.dto.auth;

import lombok.Data;

@Data
public class SetupCompanyRequest {
    private String nombre;
    private String ruc;
    private String dv;
    private String ambiente; // opcional, default DEV
}
