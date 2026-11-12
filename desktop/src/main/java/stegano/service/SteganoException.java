package stegano.service;

import java.lang.RuntimeException;



public class SteganoException extends RuntimeException {

    public SteganoException(String message) {
        super(message);
    }

    public SteganoException(String message, Throwable cause) {
        super(message, cause);
    }
}