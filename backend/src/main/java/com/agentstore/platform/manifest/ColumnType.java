package com.agentstore.platform.manifest;

import java.util.Locale;

public enum ColumnType {
    STRING, NUMBER, INTEGER, BOOLEAN, DATE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A missing or unknown type is a string, per the manifest rules. */
    public static ColumnType fromWire(String value) {
        for (ColumnType type : values()) {
            if (type.wire().equals(value)) {
                return type;
            }
        }
        return STRING;
    }
}
