package com.ratones.sifenwrapper.dto.response;

import lombok.Builder;
import lombok.Data; /**
 * Respuesta genérica del wrapper.
 * Envuelve las respuestas de rshk-jsifenlib de forma consistente.
 */
@Data
@Builder
public class SifenApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private ErrorDTO error;

    public static <T> SifenApiResponse<T> ok(T data) {
        return SifenApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> SifenApiResponse<T> ok(T data, String message) {
        return SifenApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> SifenApiResponse<T> error(String message, String code) {
        return SifenApiResponse.<T>builder()
                .success(false)
                .error(ErrorDTO.builder().codigo(code).descripcion(message).build())
                .build();
    }
}
