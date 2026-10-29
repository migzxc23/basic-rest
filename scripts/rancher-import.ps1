# Starts Rancher and imports the Floci EKS cluster ("basic-rest") into it.
# Prereqs: deploy-floci-eks.ps1 has run (floci-kubeconfig exists), Docker Desktop with ~4 GB free RAM.
# Run from the repo root:  powershell -ExecutionPolicy Bypass -File .\scripts\rancher-import.ps1
param(
    [string]$Password    = "FlociRancher-2026!",   # must match CATTLE_BOOTSTRAP_PASSWORD in docker-compose.yaml
    [string]$ClusterName = "floci-eks"
)
$ErrorActionPreference = "Continue"
$ui = "https://localhost:8443"

function Rancher {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = "")
    $args = @("-sk", "-X", $Method, "-H", "Content-Type: application/json")
    if ($Token) { $args += @("-H", "Authorization: Bearer $Token") }
    if ($Body) {
        $f = New-TemporaryFile; [System.IO.File]::WriteAllText($f.FullName, ($Body | ConvertTo-Json -Depth 10), (New-Object System.Text.UTF8Encoding($false)))
        $args += @("-d", "@$($f.FullName)")
    }
    $args += "$ui$Path"
    $out = & curl.exe @args
    if ($Body) { Remove-Item $f -Force }
    if ($out) { try { return $out | ConvertFrom-Json } catch { return $out } }
}

Write-Host "== 1/5 Start Rancher (first start downloads ~1 GB and takes several minutes)"
docker compose --profile rancher up -d rancher
if ($LASTEXITCODE -ne 0) { throw "could not start rancher" }
for ($i = 0; $i -lt 90; $i++) {
    $pong = (& curl.exe -sk "$ui/ping") 2>$null
    if ($pong -eq "pong") { break }
    Start-Sleep -Seconds 10; Write-Host "  waiting for Rancher..."
}
if ($pong -ne "pong") { docker logs rancher --tail 30; throw "Rancher did not become ready" }

Write-Host "== 2/5 Log in and set the server URL (reachable from inside the k3s cluster)"
$login = Rancher POST "/v3-public/localProviders/local?action=login" @{ username = "admin"; password = $Password; responseType = "json" }
$token = $login.token
if (-not $token) { throw "Rancher login failed: $login" }
$nets = docker inspect rancher --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}={{$v.IPAddress}} {{end}}'
if ($nets -match 'floci-net=([\d.]+)') { $rIp = $Matches[1] } else { throw "rancher is not on floci-net ($nets)" }
Rancher PUT "/v3/settings/server-url" @{ name = "server-url"; value = "https://$rIp" } $token | Out-Null
Rancher PUT "/v3/settings/first-login" @{ name = "first-login"; value = "false" } $token | Out-Null

Write-Host "== 3/5 Create the imported cluster '$ClusterName'"
$cl = Rancher POST "/v3/clusters" @{ type = "cluster"; name = $ClusterName } $token
$clusterId = $cl.id
if (-not $clusterId) { throw "could not create cluster: $cl" }
$reg = Rancher POST "/v3/clusterregistrationtokens" @{ type = "clusterRegistrationToken"; clusterId = $clusterId } $token
$manifestUrl = $null
for ($i = 0; $i -lt 20 -and -not $manifestUrl; $i++) {
    Start-Sleep -Seconds 3
    $t = Rancher GET "/v3/clusterregistrationtokens/$($reg.id)" $null $token
    $manifestUrl = $t.manifestUrl
}
if (-not $manifestUrl) { throw "no registration manifest URL returned" }

Write-Host "== 4/5 Download the agent manifest and apply it to the Floci cluster"
$path = ([uri]$manifestUrl).AbsolutePath
$yaml = Join-Path $PWD "rancher-agent.yaml"
& curl.exe -sk -o $yaml "$ui$path"
$env:KUBECONFIG = Join-Path $PWD "floci-kubeconfig"
kubectl apply -f $yaml
if ($LASTEXITCODE -ne 0) { throw "applying the Rancher agent manifest failed" }

Write-Host "== 5/5 Wait for the cluster to turn Active"
for ($i = 0; $i -lt 40; $i++) {
    $c = Rancher GET "/v3/clusters/$clusterId" $null $token
    if ($c.state -eq "active") { break }
    Write-Host "  cluster state: $($c.state)"; Start-Sleep -Seconds 10
}
Write-Host "`nOpen $ui (admin / $Password), then Cluster Management -> $ClusterName -> Workloads -> namespace basic-rest."
