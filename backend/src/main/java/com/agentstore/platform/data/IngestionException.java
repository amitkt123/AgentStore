package com.agentstore.platform.data;

/** The uploaded file cannot be read as its format. The message is shown to the buyer, so keep it plain. */
public class IngestionException extends RuntimeException {
    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
