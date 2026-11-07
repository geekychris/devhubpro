package io.devportal.asset.error;

/** The request is malformed (missing or invalid fields). Mapped to HTTP 400. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
