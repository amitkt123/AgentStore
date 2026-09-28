package com.agentstore.platform.verification;

/** Fixed order: ABOUT then TAX_IDS then PHONE then AGREEMENT. {@code next()} loops on itself at the last step. */
public enum VerificationStep {
    ABOUT, TAX_IDS, PHONE, AGREEMENT;

    public VerificationStep next() {
        return switch (this) {
            case ABOUT -> TAX_IDS;
            case TAX_IDS -> PHONE;
            case PHONE -> AGREEMENT;
            case AGREEMENT -> AGREEMENT;
        };
    }
}
