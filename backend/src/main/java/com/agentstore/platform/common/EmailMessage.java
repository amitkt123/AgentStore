package com.agentstore.platform.common;

/** A plain-text email. {@code to} is always a normalized (lowercase) address. */
public record EmailMessage(String to, String subject, String text) {
}
