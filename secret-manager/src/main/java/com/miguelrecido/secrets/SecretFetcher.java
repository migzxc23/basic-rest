package com.miguelrecido.secrets;

import java.util.Map;

@FunctionalInterface
public interface SecretFetcher {

    /** @return the flat key/value content of the configured secret */
    Map<String, String> fetch(SecretsManagerProperties properties);
}
