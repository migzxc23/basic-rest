package com.miguelrecido.secrets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration(proxyBeanMethods = false)
class SecretsManagerMarkerConfiguration {

    /** Exposes only non-sensitive facts (never values) about the secret source. */
    @Bean
    SecretsManagerStatus secretsManagerStatus(ConfigurableEnvironment env) {
        boolean loaded = env.getPropertySources().contains(SecretsManagerEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        return new SecretsManagerStatus(loaded);
    }
}
