package com.miguelrecido.secrets;

import java.util.ArrayList;
import java.util.List;

/** Bound from {@code secrets.manager.*}. */
public class SecretsManagerProperties {

    public static final String PREFIX = "secrets.manager";

    private boolean enabled = false;
    private String secretId;
    private String region;
    /** Optional endpoint override, e.g. http://localhost:4566 for Floci. */
    private String endpoint;
    private boolean failFast = true;
    /** Only these JSON keys are imported into the Spring Environment. */
    private List<String> allowedKeys = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSecretId() { return secretId; }
    public void setSecretId(String secretId) { this.secretId = secretId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public boolean isFailFast() { return failFast; }
    public void setFailFast(boolean failFast) { this.failFast = failFast; }
    public List<String> getAllowedKeys() { return allowedKeys; }
    public void setAllowedKeys(List<String> allowedKeys) { this.allowedKeys = allowedKeys; }
}
