param([string]$K3dExecutable='k3d',[switch]$SkipBuild,[switch]$Prebuilt,[string]$OpenSsl='C:/Program Files/Git/usr/bin/openssl.exe')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Push-Location $root
function Native { $ErrorActionPreference='Continue'; $program=$args[0];$arguments=@($args|Select-Object -Skip 1); & $program @arguments 2>&1 | ForEach-Object { "$_" }; if($LASTEXITCODE -ne 0){throw "Command failed: $program"} }
try{
    [IO.Directory]::CreateDirectory((Join-Path $root '.env.k3d'))|Out-Null
    $config=Join-Path $root '.env.k3d/kubeconfig'
    $clusters=& $K3dExecutable cluster list -o json | ConvertFrom-Json
    if($LASTEXITCODE -ne 0){throw 'Cannot read k3d clusters'}
    if($clusters|Where-Object name -eq notifyhub){if(!(Test-Path -LiteralPath $config)){throw 'Cluster notifyhub already exists without this workspace kubeconfig; inspect its ownership before reusing'};Native $K3dExecutable cluster start notifyhub}
    else{Native $K3dExecutable cluster create --config infra/k3d.yaml}
    $kubeconfigText=& $K3dExecutable kubeconfig get notifyhub
    if($LASTEXITCODE -ne 0){throw 'Could not obtain dedicated kubeconfig'}
    [IO.File]::WriteAllText($config,($kubeconfigText -join "`n"),[Text.UTF8Encoding]::new($false))
    & "$PSScriptRoot/setup-k3d-secrets.ps1" -Kubeconfig $config -OpenSsl $OpenSsl
    if(!$SkipBuild){& "$PSScriptRoot/build-k3d-images.ps1" -Prebuilt:$Prebuilt}
    Native $K3dExecutable image import notifyhub/api-gateway:local notifyhub/auth-service:local notifyhub/campaign-service:local notifyhub/notification-service:local notifyhub/reporting-service:local notifyhub/frontend:local -c notifyhub
    Native kubectl --kubeconfig $config apply -k infra/k8s/overlays/k3d
    foreach($service in @('postgres','redis','rabbitmq','kafka','mailhog','tempo')){Native kubectl --kubeconfig $config -n notifyhub rollout status "deployment/$service" --timeout=300s}
    foreach($service in @('api-gateway','auth-service','campaign-service','notification-service','reporting-service','frontend')){Native kubectl --kubeconfig $config -n notifyhub rollout restart "deployment/$service";Native kubectl --kubeconfig $config -n notifyhub rollout status "deployment/$service" --timeout=300s}
    & "$PSScriptRoot/bootstrap-k3d-admin.ps1" -Kubeconfig $config
    & "$PSScriptRoot/verify-k3d-network.ps1" -Kubeconfig $config
    Write-Output 'NotifyHub: https://notifyhub.localhost:8443 (local self-signed certificate). Your default kubeconfig/context was not changed.'
}finally{Pop-Location}
