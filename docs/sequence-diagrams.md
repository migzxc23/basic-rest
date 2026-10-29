# basic-rest: sequence diagrams

Three flows: (1) pod startup with the password loaded from AWS Secrets Manager via IRSA, (2) a CRUD request, (3) the local Floci EKS deployment.

## 1. Startup: loading `DATABASE_PASSWORD` (IRSA + Secrets Manager + KMS)

The ConfigMap holds only `AWS_SECRET_ID` and `AWS_REGION`. The password is never in Kubernetes.

```mermaid
sequenceDiagram
    autonumber
    participant K as Kubernetes (EKS)
    participant Pod as basic-rest pod
    participant Lib as secret-manager<br/>(EnvironmentPostProcessor)
    participant STS as AWS STS
    participant SM as AWS Secrets Manager
    participant KMS as AWS KMS
    participant Spring as Spring (DataSource)
    participant DB as PostgreSQL (RDS)

    K->>Pod: Start pod with ServiceAccount (role-arn annotation),<br/>inject AWS_ROLE_ARN + web identity token file,<br/>ConfigMap: AWS_SECRET_ID, AWS_REGION
    Pod->>Lib: SpringApplication.run, environment prepared
    Lib->>Lib: Read secrets.manager.* (enabled, secret-id, region, allowed-keys)
    Lib->>STS: AssumeRoleWithWebIdentity (ServiceAccount token)
    STS-->>Lib: Temporary credentials (no access keys stored)
    Lib->>SM: GetSecretValue(AWS_SECRET_ID)
    SM->>KMS: Decrypt secret (permission from IAM role)
    KMS-->>SM: Plaintext
    SM-->>Lib: {"DATABASE_PASSWORD": "..."}
    alt Secret read OK
        Lib->>Lib: Keep only allowed-keys, add as first property source
        Lib-->>Pod: Log "Secret Manager is reachable", "Secrets 1 of 1 loaded"
        Pod->>Spring: Create DataSource, resolve ${DATABASE_PASSWORD}
        Spring->>DB: Connect (DATABASE_URL, DATABASE_USERNAME, password)
        DB-->>Spring: Connected
        Spring-->>Pod: Application ready, /actuator/health = UP
    else Failure (fail-fast = true)
        Lib-->>Pod: "Secret Manager is not reachable [category]" and the app stops<br/>(missing configuration / access denied / secret not found /<br/>unreadable content / contact failure)
    end
```

## 2. CRUD request

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client (Postman)
    participant C as ItemController
    participant S as ItemService
    participant R as ItemRepository (JPA)
    participant DB as PostgreSQL

    Client->>C: POST /api/items {name, description, price}
    C->>C: Validate body (@Valid)
    alt Invalid body
        C-->>Client: 400 Bad Request (ProblemDetail)
    else Valid
        C->>S: create(request)
        S->>R: save(item)
        R->>DB: INSERT INTO items
        DB-->>R: Row with generated id
        R-->>S: Item
        S-->>C: Item
        C-->>Client: 201 Created + Location /api/items/{id}
    end

    Client->>C: GET /api/items or /api/items/{id}
    C->>S: findAll() or findById(id)
    S->>R: findAll() or findById(id)
    R->>DB: SELECT
    DB-->>R: Rows
    alt Not found (by id)
        S-->>C: ItemNotFoundException
        C-->>Client: 404 Not Found (ProblemDetail)
    else Found
        C-->>Client: 200 OK + JSON
    end

    Client->>C: PUT /api/items/{id} {name, description, price}
    C->>S: update(id, request)
    S->>R: findById, then save(item)
    R->>DB: UPDATE items
    C-->>Client: 200 OK (or 404 if missing)

    Client->>C: DELETE /api/items/{id}
    C->>S: delete(id)
    S->>R: existsById, then deleteById
    R->>DB: DELETE FROM items
    C-->>Client: 204 No Content (or 404 if missing)
```

## 3. Local deployment on the Floci EKS cluster

`scripts/deploy-floci-eks.ps1`, run after `docker compose up -d`, the RDS instance creation and `scripts/seed-floci-secret.ps1`.

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer (PowerShell)
    participant Docker as Docker Desktop
    participant Floci as Floci (AWS emulator :4566)
    participant K3s as Floci EKS (k3s)
    participant Pod as basic-rest pod

    Dev->>Floci: Seed: create KMS key + KMS-encrypted secret (seed-floci-secret.ps1)
    Dev->>Docker: docker build (builds the library, then the service)
    Dev->>Floci: ECR create-repository, docker push image
    Dev->>Floci: ec2 VPC/subnets, eks create-cluster
    Floci->>K3s: Start k3s container (privileged, on floci-net)
    K3s-->>Floci: API ready, cluster ACTIVE
    Dev->>Floci: IAM OIDC provider, role with IRSA trust,<br/>policy: GetSecretValue + kms:Decrypt
    Dev->>K3s: Build kubeconfig from k3s.yaml, kubectl apply<br/>(namespace, ConfigMap with Floci IP, ServiceAccount, Deployment)
    K3s->>Floci: Pull image via ECR registry mirror
    K3s->>Pod: Start pod (projected ServiceAccount token, audience sts.amazonaws.com)
    Pod->>Floci: STS AssumeRoleWithWebIdentity
    Floci-->>Pod: Temporary credentials
    Pod->>Floci: Secrets Manager GetSecretValue
    Floci-->>Pod: {"DATABASE_PASSWORD": "..."}
    Pod->>Floci: JDBC to RDS proxy port 7001
    Pod-->>K3s: Readiness probe /actuator/health/readiness OK
    Dev->>K3s: kubectl port-forward svc/basic-rest 8080:80
    Dev->>Pod: Postman: POST/GET/PUT/DELETE /api/items
```
