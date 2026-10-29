package com.miguelrecido.secrets;

public enum FailureCategory {
    MISSING_CONFIGURATION("missing configuration"),
    ACCESS_DENIED("access denied"),
    SECRET_NOT_FOUND("secret not found"),
    UNREADABLE_CONTENT("unreadable content"),
    CONTACT_FAILURE("contact failure");

    private final String label;

    FailureCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
