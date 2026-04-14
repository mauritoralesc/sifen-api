package com.ratones.sifenwrapper.dto.response.consulta;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ConsultaDocumentoDTO {
    private String cdc;
    private LocalDateTime fechaEmision;
    private LocalDateTime fechaFirma;
    private Integer tipoEmision;
    private String tipoEmisionDescripcion;
    private String codigoSeguridad;
    private String qrUrl;
    private ConsultaTimbradoDTO timbrado;
    private ConsultaEmisorDTO emisor;
    private ConsultaReceptorDTO receptor;
    private Integer tipoTransaccion;
    private String tipoTransaccionDescripcion;
    private Integer tipoImpuesto;
    private String tipoImpuestoDescripcion;
    private String moneda;
    private ConsultaCondicionDTO condicion;
    private List<ConsultaItemDTO> items;
    private ConsultaTotalesDTO totales;
}
