package com.ratones.sifenwrapper.dto.request;

import lombok.Data;

@Data
public class AutoFacturaDTO {
    private int tipoVendedor;
    private String documentoTipo;
    private String documentoNumero;
    private String nombre;
    private String direccion;
    private int numeroCasa;
    private int departamento;
    private String departamentoDescripcion;
    private int distrito;
    private String distritoDescripcion;
    private int ciudad;
    private String ciudadDescripcion;
    private int ubicacion;
    private int distanciaKm;
    private String pais;
}
