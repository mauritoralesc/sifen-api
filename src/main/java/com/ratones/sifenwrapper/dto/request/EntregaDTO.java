package com.ratones.sifenwrapper.dto.request;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class EntregaDTO {
    // Campos comunes
    private int tipo;
    private BigDecimal monto;
    private String moneda;
    /** Tipo de cambio. Requerido cuando moneda != PYG. */
    private BigDecimal tipoCambio;

    // Tarjeta de crédito (tipo 3) o débito (tipo 4)
    /**
     * Denominación de la tarjeta (iDenTarj). Requerido para tipo 3 y 4.
     * Valores: 1=Visa, 2=Mastercard, 3=American Express, 4=Maestro, 5=Panal, 6=Cabal, 99=Otro
     */
    private Integer denominacionTarjeta;
    /**
     * Forma de procesamiento del pago (iForProPa). Requerido para tipo 3 y 4.
     * Valores: 1=POS, 2=Pago Electrónico, 9=Otro
     */
    private Integer formaProcesamiento;
    /** Razón social de la procesadora (opcional). */
    private String razonSocialProcesadora;
    /** RUC de la procesadora (opcional). */
    private String rucProcesadora;
    /** DV del RUC de la procesadora (opcional). */
    private Short dvProcesadora;
    /** Código de autorización de la operación (opcional). */
    private Long codigoAutorizacion;
    /** Nombre del titular de la tarjeta (opcional). */
    private String nombreTitular;
    /** Últimos 4 dígitos de la tarjeta (opcional). */
    private Short numerosUltimosTarjeta;

    // Cheque (tipo 2)
    /** Número de cheque. Requerido para tipo 2. */
    private String numeroCheque;
    /** Banco emisor del cheque. Requerido para tipo 2. */
    private String bancoEmisor;
}
