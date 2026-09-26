package com.agentstore.platform.storage;

/** The object store failed. Messages never contain pre-signed URLs or credentials. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
