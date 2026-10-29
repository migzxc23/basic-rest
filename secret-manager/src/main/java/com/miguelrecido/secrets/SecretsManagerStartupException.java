package com.miguelrecido.secrets;

/** Thrown when the secret cannot be read. The message never contains secret values. */
public class SecretsManagerStartupException extends IllegalStateException {

    private final FailureCategory category;

    public SecretsManagerStartupException(FailureCategory category) {
        this(category, null);
    }

    /** @param detail non-sensitive technical detail (exception type / HTTP status), shown as the "Caused by" line */
    public SecretsManagerStartupException(FailureCategory category, String detail) {
        super("Secret Manager is not reachable [" + category.label() + "]",
                detail == null ? null : new RuntimeException(detail));
        this.category = category;
    }

    public FailureCategory getCategory() {
        return category;
    }
}
