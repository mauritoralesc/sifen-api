package com.ratones.sifenwrapper.dto.response.consulta;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConsultaReceptorDTO {
    private String ruc;
    private Integer dv;
    private Integer tipoDocumentoIdentidad;
    private String descripcionTipoDocumento;
    private String numeroDocumento;
    private String razonSocial;
    private String nombreFantasia;
    private String direccion;
    private Integer numeroCasa;
    private Integer departamento;
    private String departamentoDescripcion;
    private Integer distrito;
    private String distritoDescripcion;
    private Integer ciudad;
    private String ciudadDescripcion;
    private String telefono;
    private String celular;
    private String email;
    private String codigoCliente;
    private Integer tipoOperacion;
    private String tipoOperacionDescripcion;
    private Integer tipoContribuyente;
    private String tipoContribuyenteDescripcion;
}
