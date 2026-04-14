package com.ratones.sifenwrapper.exception;

import com.ratones.sifenwrapper.dto.response.SifenApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SifenServiceException.class)
    public ResponseEntity<SifenApiResponse<Void>> handleSifenServiceException(SifenServiceException ex) {
        log.error("Error del servicio SIFEN: {}", ex.getMessage(), ex);
        String detalle = ex.getSifenException() != null
                ? ex.getSifenException().getMessage()
                : ex.getMessage();
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(SifenApiResponse.error(detalle, "SIFEN_ERROR"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<SifenApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Argumento inválido: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(SifenApiResponse.error(ex.getMessage(), "INVALID_REQUEST"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<SifenApiResponse<Void>> handleBadRequest(HttpMessageNotReadableException ex) {
        log.warn("Request body no legible: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(SifenApiResponse.error(
                        "El cuerpo de la solicitud no es válido. Verifique el formato JSON y los tipos de datos.",
                        "INVALID_JSON"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<SifenApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detalle = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(ex.getMessage());
        log.warn("Error de validación: {}", detalle);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(SifenApiResponse.error(detalle, "VALIDATION_ERROR"));
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<SifenApiResponse<Void>> handleNullPointer(NullPointerException ex) {
        log.error("NullPointerException: {}", ex.getMessage(), ex);
        String descripcion = "Error de datos: un campo obligatorio es nulo. Detalle: " + ex.getMessage();
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(SifenApiResponse.error(descripcion, "MISSING_FIELD"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SifenApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Error inesperado [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        String descripcion = "Error interno del servidor: " + ex.getClass().getSimpleName()
                + " - " + ex.getMessage();
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(SifenApiResponse.error(descripcion, "INTERNAL_ERROR"));
    }
}
