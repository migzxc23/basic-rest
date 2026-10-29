package com.miguelrecido.secrets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Runs after config files are loaded (so {@code secrets.manager.*} is readable) but before
 * any bean - in particular the DataSource - is created. Imported keys take precedence over
 * everything else, so {@code ${DATABASE_PASSWORD}} resolves to the value from Secrets Manager.
 */
public class SecretsManagerEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "secretsManager";

    private final Log log;
    private SecretFetcher fetcher = new AwsSecretFetcher();

    public SecretsManagerEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SecretsManagerEnvironmentPostProcessor.class);
    }

    /** Test hook. */
    void setFetcher(SecretFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public int getOrder() {
        // after ConfigDataEnvironmentPostProcessor (HIGHEST_PRECEDENCE + 10)
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        SecretsManagerProperties props = Binder.get(environment)
                .bindOrCreate(SecretsManagerProperties.PREFIX, SecretsManagerProperties.class);
        if (!props.isEnabled()) {
            log.debug("Secrets Manager integration is disabled");
            return;
        }
        try {
            validate(props);
            Map<String, String> content = fetcher.fetch(props);

            Map<String, Object> imported = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            for (String key : props.getAllowedKeys()) {
                if (content.containsKey(key)) {
                    imported.put(key, content.get(key));
                } else {
                    missing.add(key);
                }
            }
            log.info("Secret Manager is reachable");
            log.info("Secrets " + imported.size() + " of " + props.getAllowedKeys().size() + " loaded");
            if (!missing.isEmpty()) {
                log.warn("Keys missing from the secret: " + missing);
            }
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, imported));
        } catch (SecretsManagerStartupException e) {
            if (props.isFailFast()) {
                throw e;
            }
            log.warn(e.getMessage() + " - continuing because fail-fast=false");
        }
    }

    private static void validate(SecretsManagerProperties p) {
        if (isBlank(p.getSecretId()) || isBlank(p.getRegion()) || p.getAllowedKeys().isEmpty()) {
            throw new SecretsManagerStartupException(FailureCategory.MISSING_CONFIGURATION);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
