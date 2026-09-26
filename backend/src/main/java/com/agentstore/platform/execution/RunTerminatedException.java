package com.agentstore.platform.execution;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The run is already terminal (or was stopped meanwhile). 409, the same message the legacy callback always gave. */
public class RunTerminatedException extends ResponseStatusException {
    public RunTerminatedException(RunStatus status) {
        super(HttpStatus.CONFLICT, "Run not accepting events: " + status);
    }
}
