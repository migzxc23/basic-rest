# Creates (or reuses) a KMS key and a KMS-encrypted Secrets Manager secret in the local Floci emulator.
# Safe to re-run. Requires the AWS CLI. Run from the repo root:  .\scripts\seed-floci-secret.ps1
param(
    [string]$Endpoint   = "http://localhost:4566",
    [string]$Region     = "us-east-1",
    [string]$SecretName = "dev/local/local/default/basic-rest",
    [string]$Password   = "secret123"
)
# PowerShell 5.1 treats native stderr as fatal under "Stop"; we check exit codes explicitly instead.
$ErrorActionPreference = "Continue"
$env:AWS_ACCESS_KEY_ID = "test"; $env:AWS_SECRET_ACCESS_KEY = "test"; $env:AWS_DEFAULT_REGION = $Region

$keyId = (aws kms describe-key --key-id alias/basic-rest --query KeyMetadata.KeyId --output text --endpoint-url $Endpoint 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $keyId) {
    Write-Host "Creating KMS key + alias/basic-rest"
    $keyId = (aws kms create-key --description "basic-rest secrets key" --query KeyMetadata.KeyId --output text --endpoint-url $Endpoint | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $keyId) { throw "kms create-key failed (is Floci running on $Endpoint?)" }
    aws kms create-alias --alias-name alias/basic-rest --target-key-id $keyId --endpoint-url $Endpoint
    if ($LASTEXITCODE -ne 0) { throw "kms create-alias failed" }
} else {
    Write-Host "Reusing KMS key $keyId"
}

$tmp = New-TemporaryFile
try {
    [System.IO.File]::WriteAllText($tmp.FullName, ('{"DATABASE_PASSWORD":"' + $Password + '"}'), (New-Object System.Text.UTF8Encoding($false)))
    aws secretsmanager describe-secret --secret-id $SecretName --endpoint-url $Endpoint 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Secret exists - updating its value"
        aws secretsmanager put-secret-value --secret-id $SecretName --secret-string "file://$($tmp.FullName)" --endpoint-url $Endpoint
    } else {
        aws secretsmanager create-secret --name $SecretName --kms-key-id alias/basic-rest `
            --secret-string "file://$($tmp.FullName)" --endpoint-url $Endpoint
    }
    if ($LASTEXITCODE -ne 0) { throw "secretsmanager call failed" }
} finally { Remove-Item $tmp -Force }

Write-Host "Done. Secret '$SecretName' is encrypted with KMS key $keyId."
