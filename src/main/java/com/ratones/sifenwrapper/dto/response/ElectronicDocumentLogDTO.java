package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ElectronicDocumentLogDTO {
    private Long id;
    private Long companyId;
    private String cdc;
    private Short tipoDocumento;
    private String numero;
    private String establecimiento;
    private String punto;
    private String estado;
    private String nroLote;
    private String sifenCodigo;
    private String sifenMensaje;
    private Object requestData;
    private Object responseData;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private LocalDateTime processedAt;
}
