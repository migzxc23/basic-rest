package com.miguelrecido.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.mock.env.MockEnvironment;

class SecretsManagerEnvironmentPostProcessorTest {

    private SecretsManagerEnvironmentPostProcessor processor(SecretFetcher fetcher) {
        SecretsManagerEnvironmentPostProcessor p = new SecretsManagerEnvironmentPostProcessor(new DeferredLogs());
        p.setFetcher(fetcher);
        return p;
    }

    private MockEnvironment enabledEnv() {
        return new MockEnvironment()
                .withProperty("secrets.manager.enabled", "true")
                .withProperty("secrets.manager.secret-id", "some/secret")
                .withProperty("secrets.manager.region", "ap-southeast-1")
                .withProperty("secrets.manager.allowed-keys", "DATABASE_PASSWORD");
    }

    @Test
    void importsOnlyAllowedKeys() {
        MockEnvironment env = enabledEnv();
        env.setProperty("spring.datasource.password", "${DATABASE_PASSWORD}");

        processor(p -> Map.of("DATABASE_PASSWORD", "s3cret", "OTHER", "nope")).postProcessEnvironment(env, null);

        assertThat(env.getProperty("DATABASE_PASSWORD")).isEqualTo("s3cret");
        assertThat(env.getProperty("OTHER")).isNull();
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("s3cret");
    }

    @Test
    void doesNothingWhenDisabled() {
        MockEnvironment env = new MockEnvironment();
        processor(p -> { throw new AssertionError("must not be called"); }).postProcessEnvironment(env, null);
        assertThat(env.getPropertySources().contains(SecretsManagerEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    void failsFastOnMissingConfiguration() {
        MockEnvironment env = new MockEnvironment().withProperty("secrets.manager.enabled", "true");
        assertThatThrownBy(() -> processor(p -> Map.of()).postProcessEnvironment(env, null))
                .isInstanceOf(SecretsManagerStartupException.class)
                .hasMessage("Secret Manager is not reachable [missing configuration]");
    }

    @Test
    void failFastFalseContinues() {
        MockEnvironment env = enabledEnv().withProperty("secrets.manager.fail-fast", "false");
        processor(p -> { throw new SecretsManagerStartupException(FailureCategory.CONTACT_FAILURE); })
                .postProcessEnvironment(env, null);
        assertThat(env.getProperty("DATABASE_PASSWORD")).isNull();
    }
}
