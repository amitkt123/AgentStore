package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Turns a stored cell (a string from CSV, a JSON value from a JSON file) into the declared type. Null and, for typed
 * columns, blank cells are null: "required" means the column is present, not that every cell has a value.
 */
public final class ValueCoercer {

    public static final class NotCoercible extends RuntimeException {
        NotCoercible(String message) {
            super(message, null, false, false);
        }
    }

    private ValueCoercer() {
    }

    public static Object coerce(ColumnType type, Object value) {
        if (value == null) {
            return null;
        }
        if (type != ColumnType.STRING && value instanceof String s && s.isBlank()) {
            return null;
        }
        return switch (type) {
            case STRING -> string(value);
            case NUMBER -> decimal(value, type);
            case INTEGER -> integer(value);
            case BOOLEAN -> bool(value);
            case DATE -> date(value);
        };
    }

    public static boolean canCoerce(ColumnType type, Object value) {
        try {
            coerce(type, value);
            return true;
        } catch (NotCoercible e) {
            return false;
        }
    }

    private static String string(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        throw new NotCoercible("not a string");
    }

    private static BigDecimal decimal(Object value, ColumnType type) {
        try {
            if (value instanceof BigDecimal b) {
                return b;
            }
            if (value instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (value instanceof String s) {
                return new BigDecimal(s.strip());
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        throw new NotCoercible("not a valid " + type.wire());
    }

    private static Long integer(Object value) {
        try {
            return decimal(value, ColumnType.INTEGER).longValueExact();
        } catch (ArithmeticException e) {
            throw new NotCoercible("not a valid integer");
        }
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String t = s.strip();
            if (t.equalsIgnoreCase("true")) {
                return true;
            }
            if (t.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw new NotCoercible("not a valid boolean");
    }

    private static String date(Object value) {
        if (value instanceof String s) {
            try {
                LocalDate.parse(s.strip());
                return s.strip();
            } catch (DateTimeParseException e) {
                // fall through
            }
        }
        throw new NotCoercible("not a valid date");
    }
}
