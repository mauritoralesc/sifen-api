package com.ratones.sifenwrapper.security;

/**
 * Almacena el companyId del tenant actual en un ThreadLocal.
 * Se establece en los filtros de autenticación (JWT / API Key)
 * y se limpia al finalizar la request.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_COMPANY = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long companyId) {
        CURRENT_COMPANY.set(companyId);
    }

    public static Long get() {
        return CURRENT_COMPANY.get();
    }

    public static void clear() {
        CURRENT_COMPANY.remove();
    }
}
