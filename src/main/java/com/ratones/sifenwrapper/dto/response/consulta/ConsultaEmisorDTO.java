package com.ratones.sifenwrapper.dto.response.consulta;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ConsultaEmisorDTO {
    private String ruc;
    private String dv;
    private Integer tipoContribuyente;
    private String tipoContribuyenteDescripcion;
    private Integer tipoRegimen;
    private String tipoRegimenDescripcion;
    private String razonSocial;
    private String nombreFantasia;
    private String direccion;
    private String numeroCasa;
    private Integer departamento;
    private String departamentoDescripcion;
    private Integer distrito;
    private String distritoDescripcion;
    private Integer ciudad;
    private String ciudadDescripcion;
    private String telefono;
    private String email;
    private String denominacionSucursal;
    private List<ConsultaActividadEconomicaDTO> actividadesEconomicas;
}
