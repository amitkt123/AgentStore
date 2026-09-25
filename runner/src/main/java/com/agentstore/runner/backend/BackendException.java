package com.agentstore.runner.backend;

/** Messages must never contain env values or other request secrets. */
public class BackendException extends RuntimeException {
    public BackendException(String message) { super(message); }
    public BackendException(String message, Throwable cause) { super(message, cause); }
}
