package com.ratones.sifenwrapper.exception;

import com.roshka.sifen.core.exceptions.SifenException;

public class SifenServiceException extends RuntimeException {

    private final SifenException cause;

    public SifenServiceException(String message, SifenException cause) {
        super(message + ": " + cause.getMessage(), cause);
        this.cause = cause;
    }

    public SifenException getSifenException() {
        return cause;
    }
}
