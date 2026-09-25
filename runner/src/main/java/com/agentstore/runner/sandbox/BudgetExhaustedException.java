package com.agentstore.runner.sandbox;

public class BudgetExhaustedException extends RuntimeException {
    public static final String CAPACITY_EXHAUSTED = "CAPACITY_EXHAUSTED";
    public static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";

    private final String code;

    public BudgetExhaustedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
