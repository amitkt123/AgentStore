package com.agentstore.platform.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretEncryptionPropertiesTest {

    @Test
    void acceptsA32ByteKey() {
        var properties = new SecretEncryptionProperties("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

        assertThat(properties.keyBytes()).hasSize(32);
    }

    @Test
    void rejectsAKeyThatIsNotAnAesLength() {
        // 23 bytes: the old default, which only failed on the first encryption
        assertThatThrownBy(() -> new SecretEncryptionProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECRET_ENCRYPTION_KEY");
    }

    @Test
    void rejectsAMissingKey() {
        assertThatThrownBy(() -> new SecretEncryptionProperties(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECRET_ENCRYPTION_KEY");
    }
}
