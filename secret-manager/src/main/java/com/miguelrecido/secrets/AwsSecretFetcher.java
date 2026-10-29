package com.miguelrecido.secrets;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.protocols.jsoncore.JsonNode;
import software.amazon.awssdk.protocols.jsoncore.JsonNodeParser;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Reads one secret from AWS Secrets Manager.
 * <p>
 * Credentials come from the AWS default provider chain. On EKS with IRSA the pod has
 * {@code AWS_ROLE_ARN} and {@code AWS_WEB_IDENTITY_TOKEN_FILE}, so the chain exchanges the
 * ServiceAccount token for short-lived credentials via STS - no access keys are stored anywhere.
 * KMS decryption of the secret is performed by Secrets Manager on behalf of the role.
 */
public class AwsSecretFetcher implements SecretFetcher {

    private static final Set<String> DENIED_CODES = Set.of(
            "AccessDeniedException", "UnrecognizedClientException", "InvalidIdentityToken",
            "ExpiredTokenException", "ExpiredToken", "InvalidClientTokenId");

    @Override
    public Map<String, String> fetch(SecretsManagerProperties p) {
        SecretsManagerClientBuilder builder = SecretsManagerClient.builder()
                .region(Region.of(p.getRegion()));
        if (p.getEndpoint() != null && !p.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(p.getEndpoint()));
        }
        try (DefaultCredentialsProvider credentials = DefaultCredentialsProvider.create()) {
            builder.credentialsProvider(credentials);
            try (SecretsManagerClient client = builder.build()) {
                GetSecretValueResponse response = client.getSecretValue(
                        GetSecretValueRequest.builder().secretId(p.getSecretId()).build());
                return parseFlatJson(response.secretString());
            }
        } catch (ResourceNotFoundException e) {
            throw new SecretsManagerStartupException(FailureCategory.SECRET_NOT_FOUND);
        } catch (AwsServiceException e) {
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            String msg = e.awsErrorDetails() == null ? e.getMessage() : e.awsErrorDetails().errorMessage();
            String detail = "HTTP " + e.statusCode() + " " + code + ": " + msg;
            if (e.statusCode() == 403 || (code != null && DENIED_CODES.contains(code))) {
                throw new SecretsManagerStartupException(FailureCategory.ACCESS_DENIED, detail);
            }
            throw new SecretsManagerStartupException(FailureCategory.CONTACT_FAILURE, detail);
        } catch (SdkClientException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            throw new SecretsManagerStartupException(FailureCategory.CONTACT_FAILURE,
                    e.getMessage() + " | root cause: " + root.getClass().getName() + ": " + root.getMessage());
        }
    }

    /** Parses {@code {"KEY":"value", ...}}. Only flat string values are accepted. */
    static Map<String, String> parseFlatJson(String json) {
        if (json == null || json.isBlank()) {
            throw new SecretsManagerStartupException(FailureCategory.UNREADABLE_CONTENT);
        }
        try {
            JsonNode node = JsonNodeParser.create().parse(json);
            if (!node.isObject()) {
                throw new SecretsManagerStartupException(FailureCategory.UNREADABLE_CONTENT);
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : node.asObject().entrySet()) {
                if (!entry.getValue().isString()) {
                    throw new SecretsManagerStartupException(FailureCategory.UNREADABLE_CONTENT);
                }
                result.put(entry.getKey(), entry.getValue().asString());
            }
            return result;
        } catch (SecretsManagerStartupException e) {
            throw e;
        } catch (RuntimeException e) {
            // deliberately no cause: parser messages could echo secret content
            throw new SecretsManagerStartupException(FailureCategory.UNREADABLE_CONTENT);
        }
    }
}
