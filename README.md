# basic-rest

Spring Boot 4 / Java 21 CRUD REST service (`/api/items`) on PostgreSQL. The database password is **not** stored in
Kubernetes: `application.yaml` only references `${DATABASE_PASSWORD}`, and the `secret-manager` library loads it from
AWS Secrets Manager (encrypted with KMS, authenticated with IRSA) before the DataSource starts.
Locally everything runs on **Floci**, a free AWS emulator (RDS, Secrets Manager, KMS, ECR, EKS).

```
basic-rest/                 Spring Boot service
secret-manager/             Library: Secrets Manager + KMS + IRSA loader
basic-rest-deployment/      configmap.yaml, deployment.yaml, serviceaccount.yaml, service-entry.yaml, aws/ (EKS)
                            floci-eks/ (local Floci cluster), local/ (local ConfigMap)
scripts/                    seed-floci-secret.ps1, deploy-floci-eks.ps1, rancher-import.ps1
docs/sequence-diagrams.md   Startup, CRUD and deployment sequence diagrams
docker-compose.yaml         Floci (+ optional Rancher)
```

## Prerequisites (for running on Floci)

Windows 10/11 with PowerShell 5.1 or newer. Commands below are PowerShell.

| Need | Why | Check | Install |
|---|---|---|---|
| Docker Desktop (WSL2 backend), **running** | Floci starts real containers (Postgres, k3s, ECR registry) through the Docker socket | `docker version` | https://www.docker.com/products/docker-desktop |
| Docker Compose v2 | starts Floci | `docker compose version` | included with Docker Desktop |
| AWS CLI v2 | creates RDS / secret / cluster in Floci | `aws --version` | `winget install Amazon.AWSCLI` |
| kubectl | talks to the Floci EKS cluster | `kubectl version --client` | `winget install Kubernetes.kubectl` |
| Git | clone the repo | `git --version` | `winget install Git.Git` |
| JDK 21 + Maven 3.9 | **only** to run or test the code outside Docker. The Docker build does not need them. | `java -version`, `mvn -v` | `winget install EclipseAdoptium.Temurin.21.JDK` and `winget install Apache.Maven` |
| Postman (or any HTTP client) | test the API | | https://www.postman.com/downloads |

Open a **new** PowerShell window after installing anything so the PATH refreshes.

**Resources**
- Docker Desktop memory of **8 GB or more** (Settings, Resources). Floci + k3s + Postgres + the app need about 4 GB;
  the optional Rancher adds about 3 GB.
- About 10 GB of free disk for images (Floci, Postgres, `rancher/k3s`, Maven and Temurin build images).
- Internet access for the first run, to pull those images and the Maven dependencies.

**Free local ports:** `4566` (Floci), `7001-7099` (RDS), `6500-6599` (Floci EKS API), `8080` (port-forward),
`8443` (Rancher, optional). Stop any other Postgres that already uses `7001`.

**Floci specifics**
- No real AWS account or credentials are needed. Use dummy keys: `test` / `test` for RDS, Secrets Manager and KMS.
  Floci's EKS rejects `test` / `test`, so the deploy script uses its own dummy key pair.
- Floci keeps its state in memory by default. After restarting the Floci container, re-run steps 2 and 3 below.
- The first EKS cluster creation downloads `rancher/k3s` and takes a few minutes.

## Setup and test, step by step

Run all steps from the repository root.

**1. Start Floci**
```powershell
docker compose up -d
docker logs floci --tail 5          # wait until it is up
```

**2. Create the RDS PostgreSQL instance**
```powershell
$env:AWS_ACCESS_KEY_ID="test"; $env:AWS_SECRET_ACCESS_KEY="test"; $env:AWS_DEFAULT_REGION="us-east-1"
aws rds create-db-instance --db-instance-identifier basicrest --db-instance-class db.t3.micro --engine postgres --master-username admin --master-user-password secret123 --allocated-storage 20 --db-name basicrest --endpoint-url http://localhost:4566
aws rds describe-db-instances --db-instance-identifier basicrest --query "DBInstances[0].[DBInstanceStatus,Endpoint.Port]" --endpoint-url http://localhost:4566
```
Wait until the status is `available` and the port is `7001`.

**3. Store the database password in a KMS-encrypted secret**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\seed-floci-secret.ps1
```

**4. Deploy to the Floci EKS cluster (k3s + IRSA)**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy-floci-eks.ps1
```
This builds the library and the service image, pushes it to Floci ECR, creates the cluster, the IRSA role and the
Kubernetes resources, and prints the startup log lines. Success looks like `Secret Manager is reachable` and
`Secrets 1 of 1 loaded`.

**5. Open the service** (new PowerShell window; leave it open)
```powershell
$env:KUBECONFIG="$PWD\floci-kubeconfig"
kubectl -n basic-rest get pods                                  # expect 1/1 Running
kubectl -n basic-rest port-forward svc/basic-rest 8080:80
```

**6. Test in Postman.** For POST and PUT set Body, raw, **JSON**.

| Action | Request | Body | Expect |
|---|---|---|---|
| Create | `POST http://localhost:8080/api/items` | `{"name":"pen","description":"blue","price":1.5}` | 201 |
| List | `GET http://localhost:8080/api/items` | | 200 |
| Get | `GET http://localhost:8080/api/items/1` | | 200 (404 if missing) |
| Update | `PUT http://localhost:8080/api/items/1` | `{"name":"pen","description":"red","price":2}` | 200 |
| Delete | `DELETE http://localhost:8080/api/items/1` | | 204 |

PowerShell alternative to Postman: `Invoke-RestMethod http://localhost:8080/api/items`
(note: `curl` in PowerShell is an alias for `Invoke-WebRequest`; use `curl.exe` for real curl).

## Run without Kubernetes (optional)

```powershell
cd secret-manager; mvn clean install; cd ..\basic-rest
mvn spring-boot:run "-Dspring-boot.run.profiles=local"      # no AWS: password comes from application-local.yaml
```
To load the password from Floci Secrets Manager instead (after steps 1-3):
```powershell
$env:AWS_SECRET_ID="dev/local/local/default/basic-rest"; $env:AWS_REGION="us-east-1"
$env:AWS_ENDPOINT_URL="http://localhost:4566"; $env:AWS_ACCESS_KEY_ID="test"; $env:AWS_SECRET_ACCESS_KEY="test"
$env:DATABASE_URL="jdbc:postgresql://localhost:7001/basicrest"; $env:DATABASE_USERNAME="admin"
mvn spring-boot:run
```

## Rancher UI (optional, experimental)
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\rancher-import.ps1
```
Opens at https://localhost:8443 (user `admin`, password `FlociRancher-2026!`; self-signed certificate). The import
can stay at "Waiting for API to be available" on some setups; it does not affect the application. Stop it with
`docker compose --profile rancher stop rancher`.

## Real EKS (not Floci)
- `basic-rest-deployment/configmap.yaml` and `deployment.yaml` are the EKS versions. Fill in the `<...>` values
  provided by the platform team: `AWS_SECRET_ID` (secret name), `AWS_REGION`, the RDS endpoint, the ServiceAccount
  name and role ARN, and the image.
- The secret must be flat JSON containing `DATABASE_PASSWORD`.
- `service-entry.yaml` (Istio egress to AWS STS) and the IAM templates in `basic-rest-deployment/aws/` are for the
  ticket or the platform team. Do not apply them to the local cluster.
- The pipeline needs `secret-manager` published to your Maven repository; the Dockerfile only builds it from this repo.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `aws` is not recognized | Install the AWS CLI and open a new PowerShell window |
| `aws ... --version` prints the CLI version only | The option is `--kubernetes-version` for EKS |
| `curl` rejects `-H` / `-d` | PowerShell alias; use `curl.exe` or `Invoke-RestMethod` |
| Postman returns 415 | Body must be raw, **JSON** (adds the `Content-Type` header) |
| `release version 25 not supported` | Project targets Java 21; use JDK 21 |
| Pod `CrashLoopBackOff`, log shows `Secret Manager is not reachable [...]` | The `Caused by:` line names the reason (DNS or network, access denied, secret not found) |
| `kubectl` says credentials are required | Use the generated `floci-kubeconfig` (the deploy script writes it) |
| Everything gone after restarting Floci | Floci state is in memory; redo steps 2 to 4 |

## Clean up
```powershell
docker compose --profile rancher down
docker rm -f $(docker ps -aq --filter "name=floci")
```
