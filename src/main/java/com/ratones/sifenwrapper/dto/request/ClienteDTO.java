package com.ratones.sifenwrapper.dto.request;

import lombok.Data;

@Data
public class ClienteDTO {
    private boolean contribuyente;
    private String ruc;
    private Integer documentoTipo;
    private String documentoNumero;
    private Integer tipoDocumento;
    private String numeroDocumento;
    private Integer tipoDocumentoIdentidad;
    private String numeroDocumentoIdentidad;
    private Integer iTipIDRec;
    private String dNumIDRec;
    private String razonSocial;
    private String nombreFantasia;
    private int tipoOperacion;
    private String direccion;
    private String numeroCasa;
    private int departamento;
    private String departamentoDescripcion;
    private int distrito;
    private String distritoDescripcion;
    private int ciudad;
    private String ciudadDescripcion;
    private String pais;
    private String paisDescripcion;
    private int tipoContribuyente;
    private String telefono;
    private String email;
    private String codigo;
}
