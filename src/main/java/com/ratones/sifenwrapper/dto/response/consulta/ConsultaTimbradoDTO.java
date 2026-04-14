package com.ratones.sifenwrapper.dto.response.consulta;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class ConsultaTimbradoDTO {
    private Integer tipoDocumento;
    private String tipoDocumentoDescripcion;
    private Integer numero;
    private String establecimiento;
    private String puntoExpedicion;
    private String numeroDocumento;
    private LocalDate fechaInicioVigencia;
}
