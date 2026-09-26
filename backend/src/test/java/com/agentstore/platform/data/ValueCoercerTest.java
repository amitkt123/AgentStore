package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ColumnType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueCoercerTest {

    @Test
    void numbersCoerceFromStringsAndJsonNumbers() {
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, "12.50")).isEqualTo(new BigDecimal("12.50"));
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, " -3 ")).isEqualTo(new BigDecimal("-3"));
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, 7)).isEqualTo(new BigDecimal("7"));
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, new BigDecimal("1.10"))).isEqualTo(new BigDecimal("1.10"));
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, "abc")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, "1,234.5")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, "NaN")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, Double.NaN)).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.NUMBER, true)).isFalse();
    }

    @Test
    void integersMustBeWholeAndFitALong() {
        assertThat(ValueCoercer.coerce(ColumnType.INTEGER, "42")).isEqualTo(42L);
        assertThat(ValueCoercer.coerce(ColumnType.INTEGER, "12.0")).isEqualTo(12L);
        assertThat(ValueCoercer.canCoerce(ColumnType.INTEGER, "12.5")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.INTEGER, "99999999999999999999")).isFalse();
    }

    @Test
    void booleansAndDates() {
        assertThat(ValueCoercer.coerce(ColumnType.BOOLEAN, "TRUE")).isEqualTo(true);
        assertThat(ValueCoercer.coerce(ColumnType.BOOLEAN, false)).isEqualTo(false);
        assertThat(ValueCoercer.canCoerce(ColumnType.BOOLEAN, "yes")).isFalse();
        assertThat(ValueCoercer.coerce(ColumnType.DATE, "2026-02-28")).isEqualTo("2026-02-28");
        assertThat(ValueCoercer.canCoerce(ColumnType.DATE, "2026-02-30")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.DATE, "05/01/2026")).isFalse();
        assertThat(ValueCoercer.canCoerce(ColumnType.DATE, 20260105)).isFalse();
    }

    @Test
    void nullAndBlankAreNullForTypedColumnsButKeptForStrings() {
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, null)).isNull();
        assertThat(ValueCoercer.coerce(ColumnType.NUMBER, "  ")).isNull();
        assertThat(ValueCoercer.coerce(ColumnType.DATE, "")).isNull();
        assertThat(ValueCoercer.coerce(ColumnType.STRING, "")).isEqualTo("");
        assertThat(ValueCoercer.coerce(ColumnType.STRING, 5)).isEqualTo("5");
        assertThatThrownBy(() -> ValueCoercer.coerce(ColumnType.STRING, java.util.Map.of()))
                .isInstanceOf(ValueCoercer.NotCoercible.class);
    }
}
