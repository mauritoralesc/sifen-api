package com.ratones.sifenwrapper.dto.request;

import lombok.Data;

@Data
public class EstablecimientoDTO {
    private String codigo;
    private String direccion;
    private String numeroCasa;
    private int departamento;
    private String departamentoDescripcion;
    private int distrito;
    private String distritoDescripcion;
    private int ciudad;
    private String ciudadDescripcion;
    private String telefono;
    private String email;
    private String denominacion;
}
