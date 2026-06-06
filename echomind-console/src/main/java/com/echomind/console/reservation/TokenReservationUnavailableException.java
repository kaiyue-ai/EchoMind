package com.echomind.console.reservation;

public class TokenReservationUnavailableException extends RuntimeException {

    public TokenReservationUnavailableException(String message) {
        super(message);
    }

    public TokenReservationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
