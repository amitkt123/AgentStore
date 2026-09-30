package com.agentstore.platform.execution;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The decider is not a current approver for this gate. 403, code NOT_AN_APPROVER (see {@link GateApiErrors}). */
public class NotAnApproverException extends ResponseStatusException {

    public static final String CODE = "NOT_AN_APPROVER";

    public NotAnApproverException() {
        super(HttpStatus.FORBIDDEN, "You are not an approver for this gate");
    }
}
