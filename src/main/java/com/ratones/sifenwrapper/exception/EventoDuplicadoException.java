package com.ratones.sifenwrapper.exception;

/**
 * Se lanza cuando ya existe un evento vigente (ENVIADO, INDETERMINADO o APROBADO)
 * para el mismo CDC/rango + tipo de evento dentro de la misma empresa.
 *
 * Distinta de IllegalArgumentException a propósito: esa mapea a 400 INVALID_REQUEST
 * ("corregí el payload y reintentá"), lo opuesto de lo correcto acá ("pará, esto ya
 * pasó" — reenviar un evento idéntico puede bloquear el RUC entre 10 y 60 minutos).
 */
public class EventoDuplicadoException extends RuntimeException {
    public EventoDuplicadoException(String message) {
        super(message);
    }
}
