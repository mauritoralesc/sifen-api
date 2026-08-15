package com.ratones.sifenwrapper.service;

import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * Clasifica un rechazo/error SIFEN según si el CDC puede reutilizarse al reenviar
 * el documento. Basado en el Manual Técnico SIFEN v150, §6.5 (pp.27-28): un DE
 * rechazado puede reenviarse con el mismo CDC siempre que el ajuste no altere los
 * campos que lo componen; si los altera, corresponde inutilizar el número y emitir
 * uno nuevo.
 */
@UtilityClass
public class SifenRechazoClassifier {

    public enum ClasificacionReenvio { AUTOMATICO, REQUIERE_CORRECCION, NO_REENVIABLE }

    public record ClasificacionRechazo(ClasificacionReenvio clasificacion, boolean reenviable, String accionSugerida) {}

    /**
     * Códigos SIFEN cuya corrección exige modificar un campo que compone el CDC
     * (Manual Técnico v150, validación 1000/A002, pp.160-193: los campos son
     * C002, D101, D102, C005, C006, C007, D103, D002, B002, B004, A003).
     * C002, D103 y B004 no tienen código de validación propio en la tabla (solo
     * se reportan agregados vía 1000) — por eso no aparecen como entradas propias acá.
     * Reenviar con el mismo CDC no es una opción legal para estos casos: hay que
     * inutilizar el número y emitir un documento nuevo.
     */
    private static final Set<String> CODIGOS_AFECTAN_CDC = Set.of(
            "1000", // A002  - CDC no corresponde con las informaciones del XML
            "1001", // A002a - CDC duplicado
            "1002", // A002b - Documento electrónico duplicado
            "1003", // A003  - DV del CDC inválido
            "1050", // B002  - Tipo de emisión inválido en esta etapa
            "1105", // C005  - Código de establecimiento incorrecto
            "1106", // C006  - Código de punto de expedición incorrecto
            "1109", // C007  - Número de documento inutilizado anteriormente
            "1150", // D002  - Fecha/hora de emisión inválida por retraso
            "1151", // D002f - Fecha/hora de emisión inválida por envío adelantado
            "1156", // D002a - Fecha/hora de emisión anterior al lanzamiento del sistema
            "1250", // D101  - RUC del emisor inexistente
            "1251", // D101a - RUC del emisor inhabilitado para facturación electrónica
            "1252", // D101b - RUC del emisor inactivo
            "1253"  // D102  - DV del RUC del emisor incorrecto
    );

    /**
     * Códigos que se resuelven con la sola retransmisión, sin corregir datos del
     * documento (transitorios de infraestructura, o casos donde el dato que originó
     * el rechazo no forma parte del CDC ni del contenido del documento).
     */
    private static final Set<String> CODIGOS_AUTO_REENVIABLES = Set.of(
            "1004", // A004a - Firma digital adelantada (ver SifenMapper.FIRMA_BACKDATE_SECONDS)
            "1264", // D101c - RUC no habilitado para servicio síncrono; el envío por lote sí es válido
            "0140", // Firma difiere del estándar — se corrige al re-firmar en el próximo envío
            "0141", // Valor de la firma (SignatureValue) diferente del calculado por el PKI
            "0161", // Servidor de procesamiento momentáneamente sin respuesta
            "0162", // Servidor de procesamiento paralizado, sin tiempo de regreso
            "0301", // Lote no encolado para procesamiento — reintentable
            "0360"  // Lote inexistente — reintentable
    );

    /**
     * Clasifica un documento por su estado y código SIFEN. Solo documentos en
     * RECHAZADO o ERROR se consideran candidatos a reenvío.
     */
    public ClasificacionRechazo clasificar(String estado, String sifenCodigo) {
        if (!"RECHAZADO".equals(estado) && !"ERROR".equals(estado)) {
            return new ClasificacionRechazo(ClasificacionReenvio.AUTOMATICO, false,
                    "El documento no está en un estado que requiera reenvío.");
        }

        if (sifenCodigo == null || sifenCodigo.isBlank()) {
            // ERROR interno del wrapper sin código SIFEN (falla de red, certificado, etc.)
            // — no es un rechazo de validación, un reintento simple suele resolverlo.
            return new ClasificacionRechazo(ClasificacionReenvio.AUTOMATICO, true,
                    "Reenviar con POST /invoices/{cdc}/resend — es un error interno del wrapper, "
                            + "no un rechazo de SIFEN. Revise los logs si persiste tras reintentar.");
        }

        if (CODIGOS_AFECTAN_CDC.contains(sifenCodigo)) {
            return new ClasificacionRechazo(ClasificacionReenvio.NO_REENVIABLE, false,
                    "El ajuste necesario altera el CDC (código " + sifenCodigo + "): inutilice el número "
                            + "y emita un documento nuevo con el siguiente correlativo.");
        }

        if (CODIGOS_AUTO_REENVIABLES.contains(sifenCodigo)) {
            return new ClasificacionRechazo(ClasificacionReenvio.AUTOMATICO, true,
                    "Reenviar con POST /invoices/{cdc}/resend — se corrige al retransmitir, sin cambiar datos.");
        }

        return new ClasificacionRechazo(ClasificacionReenvio.REQUIERE_CORRECCION, true,
                "Corrija los datos indicados por el código " + sifenCodigo + " y reenvíe con "
                        + "POST /invoices/{cdc}/resend incluyendo el payload corregido en el body.");
    }
}
