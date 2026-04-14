package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EmisionDEResponse {
    private String cdc;              // Código de Control del Documento Electrónico
    private String estado;
    private String codigoEstado;
    private String descripcionEstado;
    private String xml;              // XML firmado generado
    private String qrUrl;            // URL del QR
    private String kude;             // KUDE en base64 (si includeKude=true)
    private List<MensajeSifenDTO> mensajes;
    private RespuestaSifenDTO respuestaSifen;
}

