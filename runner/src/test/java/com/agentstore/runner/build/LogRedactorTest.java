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
            token github_pat_11ABCDEFG0123456789abcdefghijklmnopqrstuvwxyz012345  | token [REDACTED]
            OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuv                         | OPENAI_API_KEY=[REDACTED]
            slack xoxb-1234567890-abcdefghij                                      | slack [REDACTED]
            -----BEGIN RSA PRIVATE KEY-----                                       | [REDACTED]
            git clone https://user:s3cr3t@github.com:8443/org/repo.git            | git clone https://user:[REDACTED]@github.com:8443/org/repo.git
            pip install git+https://x-oauth-basic12345@github.com/org/repo.git    | pip install git+https://[REDACTED]@github.com/org/repo.git
            git clone https://tok3n7890@github.com:8443/org/repo.git              | git clone https://[REDACTED]@github.com:8443/org/repo.git
            Collecting requests==2.32.3                                           | Collecting requests==2.32.3
            """)
    void masksKnownSecretFormatsAndLeavesOrdinaryLinesAlone(String line, String expected) {
        assertThat(LogRedactor.redact(line)).isEqualTo(expected);
    }
}
