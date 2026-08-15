package com.ratones.sifenwrapper.service;

import lombok.experimental.UtilityClass;

/**
 * Clasifica el resultado de un evento SIFEN (código de respuesta de
 * {@code recepcionEvento}) en una acción sugerida para el ERP.
 *
 * <p><b>Los rangos 4000-4010 (cancelación) y 4066-4071 (inutilización) están
 * mapeados por rango, no código por código:</b> este repo no tiene un test suite
 * ni una fuente verificada del texto exacto de cada código del Manual Técnico v150
 * para completarlos uno por uno sin arriesgar una descripción incorrecta. Antes de
 * depender de {@code accionSugerida} en producción, completar la tabla exacta desde
 * el Manual Técnico v150 (sección Eventos) — un texto vago es preferible a uno
 * confiadamente equivocado.</p>
 */
@UtilityClass
public class SifenEventoClassifier {

    public enum ResultadoEvento { APROBADO, RECHAZO_DEFINITIVO, RECHAZO_CORREGIBLE, REINTENTABLE, INDETERMINADO }

    public record ClasificacionEvento(ResultadoEvento resultado, boolean reintentable, String accionSugerida) {}

    public ClasificacionEvento clasificar(short tipoEvento, String estado, String sifenCodigo) {
        if (sifenCodigo == null || sifenCodigo.isBlank()) {
            return new ClasificacionEvento(ResultadoEvento.INDETERMINADO, false,
                    "SIFEN no devolvió un código de resultado interpretable. Verifique con " +
                    "POST /invoices/events/{id}/reconcile antes de reintentar.");
        }

        if ("0600".equals(sifenCodigo)) {
            return new ClasificacionEvento(ResultadoEvento.APROBADO, false,
                    "Evento registrado correctamente por SIFEN.");
        }

        if ("CONN_ERR".equals(sifenCodigo)) {
            return new ClasificacionEvento(ResultadoEvento.INDETERMINADO, false,
                    "Fallo de conexión con SIFEN (posible sesión SSL expirada o problema de red). " +
                    "No reenvíe de inmediato: verifique con POST /invoices/events/{id}/reconcile.");
        }

        if ("0160".equals(sifenCodigo)) {
            return new ClasificacionEvento(ResultadoEvento.RECHAZO_CORREGIBLE, false,
                    "Estructura XML rechazada (XML mal formado) — es un defecto del wrapper, no del ERP. " +
                    "Reporte el caso antes de reintentar.");
        }

        if ("0140".equals(sifenCodigo) || "0141".equals(sifenCodigo)) {
            return new ClasificacionEvento(ResultadoEvento.REINTENTABLE, true,
                    "Fallo de firma digital (código " + sifenCodigo + "), habitualmente transitorio. " +
                    "Puede reintentarse.");
        }

        if (esCodigoNumericoEnRango(sifenCodigo, 4000, 4010)) {
            return new ClasificacionEvento(ResultadoEvento.RECHAZO_DEFINITIVO, false,
                    "Rechazo de cancelación (código " + sifenCodigo + "). Causas típicas: plazo de " +
                    EventoValidator.HORAS_LIMITE_CANCELACION + "h vencido, el receptor ya registró conformidad, " +
                    "o el DTE no está en un estado aprobable. Verifique el Manual Técnico v150 antes de reintentar.");
        }

        if (esCodigoNumericoEnRango(sifenCodigo, 4066, 4071)) {
            return new ClasificacionEvento(ResultadoEvento.RECHAZO_DEFINITIVO, false,
                    "Rechazo de inutilización (código " + sifenCodigo + "). Causas típicas: el rango no " +
                    "pertenece al timbrado indicado, o algún número del rango ya fue utilizado. " +
                    "Verifique el Manual Técnico v150 antes de reintentar.");
        }

        if (esCodigoNumericoEnRango(sifenCodigo, 4000, 4019)) {
            return new ClasificacionEvento(ResultadoEvento.RECHAZO_DEFINITIVO, false,
                    "Rechazo relacionado a un evento de cancelación (código " + sifenCodigo + "). " +
                    "Consulte el Manual Técnico v150 para el detalle del código.");
        }

        if (esCodigoNumericoEnRango(sifenCodigo, 4060, 4079)) {
            return new ClasificacionEvento(ResultadoEvento.RECHAZO_DEFINITIVO, false,
                    "Rechazo relacionado a un evento de inutilización (código " + sifenCodigo + "). " +
                    "Consulte el Manual Técnico v150 para el detalle del código.");
        }

        return new ClasificacionEvento(ResultadoEvento.RECHAZO_DEFINITIVO, false,
                "SIFEN rechazó el evento con código " + sifenCodigo + ". Consulte el Manual Técnico v150 " +
                "para el detalle antes de reintentar.");
    }

    private static boolean esCodigoNumericoEnRango(String codigo, int desde, int hasta) {
        try {
            int valor = Integer.parseInt(codigo.trim());
            return valor >= desde && valor <= hasta;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
