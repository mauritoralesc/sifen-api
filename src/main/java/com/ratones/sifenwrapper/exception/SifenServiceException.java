package com.ratones.sifenwrapper.exception;

import com.roshka.sifen.core.exceptions.SifenException;

public class SifenServiceException extends RuntimeException {

    private final SifenException cause;

    public SifenServiceException(String message, SifenException cause) {
        super(message + ": " + cause.getMessage(), cause);
        this.cause = cause;
    }

    public SifenServiceException(String message) {
        super(message);
        this.cause = null;
    }

    public SifenException getSifenException() {
        return cause;
    }
}
