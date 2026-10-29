package com.miguelrecido.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AwsSecretFetcherTest {

    @Test
    void parsesFlatJson() {
        assertThat(AwsSecretFetcher.parseFlatJson("{\"DATABASE_PASSWORD\":\"s3cret\",\"A\":\"b\"}"))
                .containsEntry("DATABASE_PASSWORD", "s3cret")
                .containsEntry("A", "b");
    }

    @Test
    void rejectsPlainText() {
        assertThatThrownBy(() -> AwsSecretFetcher.parseFlatJson("just-a-password"))
                .isInstanceOf(SecretsManagerStartupException.class)
                .hasMessage("Secret Manager is not reachable [unreadable content]");
    }

    @Test
    void rejectsNestedJson() {
        assertThatThrownBy(() -> AwsSecretFetcher.parseFlatJson("{\"A\":{\"B\":\"c\"}}"))
                .isInstanceOf(SecretsManagerStartupException.class);
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> AwsSecretFetcher.parseFlatJson(" "))
                .isInstanceOf(SecretsManagerStartupException.class);
    }
}
