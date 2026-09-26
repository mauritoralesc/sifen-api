package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ConsultaRucResponse {
    private String ruc;
    private String dv;
    private String razonSocial;
    /**
     * dCodEstCons del RUC consultado (solo con codigoEstado 0502): ACT=Activo,
     * SUS=Suspensión Temporal, SAD=Suspensión Administrativa, BLQ=Bloqueado,
     * CAN=Cancelado, CDE=Cancelado Definitivo. Con SUS/CAN/CDE, SIFEN rechaza
     * una operación B2B o B2G a ese receptor (regla 1308).
     */
    private String estadoRuc;
    private String estadoRucDescripcion;
    /** dRUCFactElec: "S" si el RUC consultado es facturador electrónico, "N" si no. */
    private String facturadorElectronico;
    private String estado;
    private String codigoEstado;
    private String descripcionEstado;
    private List<MensajeSifenDTO> mensajes;
}
