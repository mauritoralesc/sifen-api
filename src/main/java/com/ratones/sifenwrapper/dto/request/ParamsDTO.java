package com.ratones.sifenwrapper.dto.request;

import lombok.Data;
import lombok.ToString;
import java.util.List;

@Data
public class ParamsDTO {
    private int version;
    private String ruc;
    private String razonSocial;
    private String nombreFantasia;
    @ToString.Exclude
    private String logoBase64;
    private List<ActividadEconomicaDTO> actividadesEconomicas;
    private String timbradoNumero;
    private String timbradoFecha;
    private int tipoContribuyente;
    private int tipoRegimen;
    private List<EstablecimientoDTO> establecimientos;
}
