# Deploys basic-rest to a Floci-emulated EKS cluster (real k3s) with IRSA.
# Prereqs: Docker Desktop, AWS CLI, kubectl, `docker compose up -d` done, RDS instance created,
#          and .\scripts\seed-floci-secret.ps1 already run.
# Run from the repo root:  .\scripts\deploy-floci-eks.ps1
$ErrorActionPreference = "Stop"
$ep      = "http://localhost:4566"
$region  = "us-east-1"
$cluster = "basic-rest"
$account = "000000000000"
$image   = "$account.dkr.ecr.$region.localhost:4566/basic-rest:0.0.1"
$roleName = "basic-rest-secrets-role"
$secretName = "dev/local/local/default/basic-rest"

# EKS auth in Floci rejects the public test/test and floci/floci pairs - use your own values.
$env:AWS_ACCESS_KEY_ID = "floci-local-key"; $env:AWS_SECRET_ACCESS_KEY = "floci-local-secret-1234"
$env:AWS_DEFAULT_REGION = $region; $env:AWS_ENDPOINT_URL = $ep

function Try-Aws { param([scriptblock]$Cmd) try { & $Cmd 2>$null | Out-Null } catch {} ; $global:LASTEXITCODE = 0 }

Write-Host "== 1/7 Build and push image to Floci ECR"
Try-Aws { aws ecr create-repository --repository-name basic-rest }
docker build -f basic-rest/Dockerfile -t $image .
if ($LASTEXITCODE -ne 0) { throw "docker build failed" }
docker push $image
if ($LASTEXITCODE -ne 0) { throw "docker push failed (does *.localhost:4566 resolve for Docker?)" }

Write-Host "== 2/7 Create EKS cluster (k3s) - first run pulls rancher/k3s, can take minutes"
# "cluster not found" is expected on the first run; PowerShell 5.1 would treat that stderr as a terminating error
$prevEap = $ErrorActionPreference; $ErrorActionPreference = "Continue"
$exists = aws eks describe-cluster --name $cluster --query cluster.status --output text 2>$null
if ($LASTEXITCODE -ne 0) { $exists = $null }
$ErrorActionPreference = $prevEap
if (-not $exists) {
    $ErrorActionPreference = "Continue"
    # EKS requires a VPC with subnets: reuse Floci's if present, otherwise create a small one.
    $subnets = (aws ec2 describe-subnets --query "Subnets[].SubnetId" --output text 2>$null | Out-String).Trim()
    if (-not $subnets) {
        Write-Host "creating VPC + 2 subnets in Floci"
        $vpc = (aws ec2 create-vpc --cidr-block 10.0.0.0/16 --query Vpc.VpcId --output text | Out-String).Trim()
        $s1 = (aws ec2 create-subnet --vpc-id $vpc --cidr-block 10.0.1.0/24 --availability-zone "${region}a" --query Subnet.SubnetId --output text | Out-String).Trim()
        $s2 = (aws ec2 create-subnet --vpc-id $vpc --cidr-block 10.0.2.0/24 --availability-zone "${region}b" --query Subnet.SubnetId --output text | Out-String).Trim()
        $subnets = "$s1 $s2"
    }
    $subnetCsv = (($subnets -split '\s+') | Where-Object { $_ } | Select-Object -First 2) -join ","
    Write-Host "using subnets: $subnetCsv"
    $out = aws eks create-cluster --name $cluster --role-arn "arn:aws:iam::${account}:role/eks-role" --kubernetes-version 1.31 `
        --resources-vpc-config "subnetIds=$subnetCsv" 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($code -ne 0 -or -not ("$out" -match "basic-rest")) { throw "create-cluster failed or returned no cluster: $out" }
    Write-Host "create-cluster accepted, waiting for ACTIVE..."
}
$status = ""
for ($i = 0; $i -lt 120; $i++) {
    $ErrorActionPreference = "Continue"
    $status = (aws eks describe-cluster --name $cluster --query cluster.status --output text 2>&1 | Out-String).Trim()
    $ErrorActionPreference = $prevEap
    if ($status -eq "ACTIVE") { break }
    if ($i -eq 3) { Write-Host "Floci logs so far:"; docker logs floci --tail 30 }
    Write-Host "  status: $status"
    Start-Sleep -Seconds 5
}
if ($status -ne "ACTIVE") {
    Write-Host "Cluster did not become ACTIVE. Floci logs:"
    docker logs floci --tail 40
    throw "cluster not ACTIVE (status: $status)"
}

Write-Host "== 3/7 OIDC provider + IAM role for IRSA"
$issuer = aws eks describe-cluster --name $cluster --query cluster.identity.oidc.issuer --output text
$issuerHost = $issuer -replace '^https?://', ''
Try-Aws { aws iam create-open-id-connect-provider --url $issuer --client-id-list sts.amazonaws.com --thumbprint-list 9e99a48a9960b14926bb7f3b02e22da2b0ab7280 }

$trust = @{
  Version = "2012-10-17"
  Statement = @(@{
    Effect = "Allow"
    Principal = @{ Federated = "arn:aws:iam::${account}:oidc-provider/$issuerHost" }
    Action = "sts:AssumeRoleWithWebIdentity"
    Condition = @{ StringEquals = @{
        "${issuerHost}:sub" = "system:serviceaccount:basic-rest:basic-rest"
        "${issuerHost}:aud" = "sts.amazonaws.com" } }
  })
} | ConvertTo-Json -Depth 10
$trustFile = New-TemporaryFile; Set-Content $trustFile $trust
Try-Aws { aws iam create-role --role-name $roleName --assume-role-policy-document "file://$($trustFile.FullName)" }
aws iam update-assume-role-policy --role-name $roleName --policy-document "file://$($trustFile.FullName)" | Out-Null

$keyArn = aws kms describe-key --key-id alias/basic-rest --query KeyMetadata.Arn --output text
$perm = @{
  Version = "2012-10-17"
  Statement = @(
    @{ Effect = "Allow"; Action = "secretsmanager:GetSecretValue"
       Resource = "arn:aws:secretsmanager:${region}:${account}:secret:${secretName}-*" },
    @{ Effect = "Allow"; Action = "kms:Decrypt"; Resource = $keyArn })
} | ConvertTo-Json -Depth 10
$permFile = New-TemporaryFile; Set-Content $permFile $perm
aws iam put-role-policy --role-name $roleName --policy-name read-basic-rest-secret --policy-document "file://$($permFile.FullName)" | Out-Null
Remove-Item $trustFile, $permFile -Force

# verify the IRSA role really exists (errors above are suppressed for the "already exists" case)
aws iam get-role --role-name $roleName --query Role.Arn --output text
if ($LASTEXITCODE -ne 0) { throw "IAM role $roleName was not created" }

# From here on rely on explicit exit-code checks (PowerShell 5.1 treats native stderr as fatal under "Stop")
$ErrorActionPreference = "Continue"
Write-Host "== 4/7 kubeconfig (admin config taken from the k3s container)"
$endpoint = aws eks describe-cluster --name $cluster --query cluster.endpoint --output text
# Floci names its containers differently from its docs (e.g. floci-aws-rds-db-*), so find k3s by image or name
$k3s = docker ps --format "{{.Names}}|{{.Image}}" | Where-Object { $_ -match "k3s|eks" } | ForEach-Object { ($_ -split "\|")[0] } | Select-Object -First 1
if (-not $k3s) {
    docker ps -a --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
    docker logs floci --tail 40
    throw "no k3s container found (see the list and Floci logs above)"
}
Write-Host "k3s container: $k3s   endpoint: $endpoint"
$raw = (docker exec $k3s cat /etc/rancher/k3s/k3s.yaml) -join "`n"
if ($LASTEXITCODE -ne 0 -or -not $raw) { throw "could not read k3s.yaml from $k3s" }
$raw = $raw -replace 'server: https://[^\s]+', "server: $endpoint"
$raw = $raw -replace 'certificate-authority-data: .*', 'insecure-skip-tls-verify: true'   # local emulator only
$kc = Join-Path $PWD "floci-kubeconfig"
[System.IO.File]::WriteAllText($kc, $raw, (New-Object System.Text.UTF8Encoding($false)))
$env:KUBECONFIG = $kc
for ($i = 0; $i -lt 30; $i++) {
    kubectl get nodes 2>$null | Out-Host
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Seconds 5
}
if ($LASTEXITCODE -ne 0) { kubectl get nodes; throw "kubectl cannot reach the cluster" }

Write-Host "== 5/7 Apply manifests"
# Pods may not be able to resolve the name "floci", so give them Floci's IP on the shared network.
$nets = docker inspect floci --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}={{$v.IPAddress}} {{end}}'
if ($nets -match 'floci-net=([\d.]+)') { $flociIp = $Matches[1] } else { throw "floci is not on the floci-net network ($nets). Re-run: docker compose up -d" }
Write-Host "Floci IP on floci-net: $flociIp"
kubectl apply -f basic-rest-deployment/floci-eks/namespace.yaml
if ($LASTEXITCODE -ne 0) { throw "apply namespace failed" }
$cm = (Get-Content basic-rest-deployment/floci-eks/configmap.yaml -Raw) -replace '//floci:', "//${flociIp}:"
$cm | kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw "apply configmap failed" }
kubectl apply -f basic-rest-deployment/floci-eks/serviceaccount.yaml -f basic-rest-deployment/floci-eks/deployment.yaml
if ($LASTEXITCODE -ne 0) { throw "apply manifests failed" }
kubectl -n basic-rest rollout restart deployment/basic-rest

Write-Host "== 6/7 Wait for rollout"
kubectl -n basic-rest rollout status deployment/basic-rest --timeout=240s
if ($LASTEXITCODE -ne 0) {
    kubectl -n basic-rest get pods
    kubectl -n basic-rest describe pod -l app=basic-rest | Select-Object -Last 25
    kubectl -n basic-rest logs deploy/basic-rest --tail 40
    throw "rollout did not complete"
}

Write-Host "== 7/7 Verify"
kubectl -n basic-rest logs deploy/basic-rest | Select-String "Secret Manager|Secrets \d+ of|Started BasicRest"
Write-Host "`nTest it (new window): `$env:KUBECONFIG='$kc'; kubectl -n basic-rest port-forward svc/basic-rest 8080:80"
Write-Host "          Postman: http://localhost:8080/api/items"
