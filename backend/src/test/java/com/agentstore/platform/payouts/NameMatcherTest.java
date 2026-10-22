package com.agentstore.platform.payouts;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class NameMatcherTest {

    @ParameterizedTest
    @CsvSource({
            "Amit Kumar Tiwari,AMIT K TIWARI,true",
            "Tiwari Amit,Amit Tiwari,true",
            "Dr. Amit Tiwari,AMIT TIWARI,true",
            "A Tiwari,Amit Tiwari,true",
            "Amit Tiwari,Rahul Tiwari,false",
            "A K,Amit Kumar,false",
            "Amit Tiwari,Amit Kumar Tiwari,false"})
    void matches(String legal, String registered, boolean expected) {
        assertThat(NameMatcher.matches(legal, registered)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void nullNeverMatches() {
        assertThat(NameMatcher.matches(null, "Amit")).isFalse();
        assertThat(NameMatcher.matches("Amit", null)).isFalse();
    }
}
