package com.agentstore.runner.build;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LogRedactorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            key=AKIAQYLPMN5HHHFPZAM2 done                                        | key=[REDACTED] done
            aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYzEXAMPLEKEY     | aws_secret_access_key = [REDACTED]
            pip install https://user:hunter2@pypi.example.com/simple              | pip install https://user:[REDACTED]@pypi.example.com/simple
            token ghp_abcdefghijklmnopqrstuvwxyz0123456789                        | token [REDACTED]
            OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuv                         | OPENAI_API_KEY=[REDACTED]
            slack xoxb-1234567890-abcdefghij                                      | slack [REDACTED]
            -----BEGIN RSA PRIVATE KEY-----                                       | [REDACTED]
            Collecting requests==2.32.3                                           | Collecting requests==2.32.3
            """)
    void masksKnownSecretFormatsAndLeavesOrdinaryLinesAlone(String line, String expected) {
        assertThat(LogRedactor.redact(line)).isEqualTo(expected);
    }
}
