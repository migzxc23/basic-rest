# secret-manager (java21)

Loads credentials from **AWS Secrets Manager** into the Spring `Environment` at startup, before the
DataSource is created.

- **KMS**: the secret is encrypted at rest with a customer-managed KMS key. The app never calls KMS; the
  IAM role just needs `kms:Decrypt` on that key (see `basic-rest-deployment/aws/`).
- **IRSA**: the AWS default credential chain picks up `AWS_ROLE_ARN` + `AWS_WEB_IDENTITY_TOKEN_FILE` injected
  by EKS and exchanges the ServiceAccount token for short-lived credentials (the `sts` module is bundled). No access keys.

## Use
```xml
<dependency>
  <groupId>com.miguelrecido.secrets</groupId>
  <artifactId>secret-manager-java21</artifactId>
  <version>1.0.0</version>
</dependency>
```
```java
@SpringBootApplication
@EnableSecretsManager
public class MyApplication { ... }
```
```yaml
secrets:
  manager:
    enabled: true
    secret-id: ${AWS_SECRET_ID}
    region: ${AWS_REGION}
    endpoint: ${AWS_ENDPOINT_URL:}     # only for emulators such as Floci
    fail-fast: true
    allowed-keys: DATABASE_PASSWORD    # only these JSON keys are imported
```
Secret content must be flat JSON of strings: `{"DATABASE_PASSWORD":"..."}`.

Startup failures log `Secret Manager is not reachable [<category>]` (missing configuration, access denied,
secret not found, unreadable content, contact failure) and, with `fail-fast=true`, stop the app. Values are never logged.

## Build
```
mvn clean install
```
