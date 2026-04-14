package com.ratones.sifenwrapper.dto.request;

import lombok.Data;
import java.util.List;

@Data
public class DataDTO {
    private int tipoDocumento;
    private String establecimiento;
    private String punto;
    private String numero;
    private String descripcion;
    private String observacion;
    private String fecha;              // LocalDateTime como String ISO
    private int tipoEmision;
    private int tipoTransaccion;
    private int tipoImpuesto;
    private String moneda;
    private String codigoSeguridad;    // dCodSeg: 9 dígitos (opcional, se deriva de numero si no se envía)
    private ClienteDTO cliente;
    private FacturaDTO factura;
    private CondicionDTO condicion;
    private List<ItemDTO> items;
    private int indicadorPresencia;
    // Campos opcionales para otros tipos de documentos
    private AutoFacturaDTO autoFactura;
    private RemisionDTO remision;
    private NotaCreditoDebitoDTO notaCreditoDebito;
}
