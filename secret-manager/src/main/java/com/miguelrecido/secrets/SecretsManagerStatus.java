package com.miguelrecido.secrets;

/** @param loaded true when secrets were imported from AWS Secrets Manager at startup */
public record SecretsManagerStatus(boolean loaded) {
}
