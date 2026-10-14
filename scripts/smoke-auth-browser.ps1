param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$project = 'notifyhub-auth-browser'
$processes = @()
$composeStarted = $false
$locationPushed = $false
$logs = Join-Path $repo 'frontend\test-results\live-services'
$keys = Join-Path $repo '.env.browser-smoke'
$gateway = 'http://127.0.0.1:18080'
$issuer = 'http://127.0.0.1:18081'
$envNames = @('JWT_ISSUER_URI','JWT_JWK_SET_URI','AUTH_SERVICE_URI','AUTH_DB_URL','AUTH_DB_USERNAME','AUTH_DB_PASSWORD',
    'AUTH_REDIS_HOST','AUTH_REDIS_PORT','AUTH_REDIS_PASSWORD','AUTH_SMTP_HOST','AUTH_SMTP_PORT','AUTH_SMTP_USERNAME','AUTH_SMTP_PASSWORD',
    'AUTH_SMTP_AUTH','AUTH_SMTP_STARTTLS','AUTH_SIGNING_KEY_PATH','AUTH_CONTROL_SECRET_PATH','AUTH_REGISTRATION_ENABLED',
    'NOTIFYHUB_GATEWAY_URL','NOTIFYHUB_MAILHOG_URL')
$savedEnv = @{}
foreach ($name in $envNames) { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name) }
$javaReadPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue' # Java writes these properties to stderr.
$javaSettings = & rtk proxy java -XshowSettings:properties -version 2>&1
$ErrorActionPreference = $javaReadPreference
$javaRuntimeDir = [string]($javaSettings | Where-Object { [string]$_ -match '^\s*java.home\s*=' } | Select-Object -First 1)
if (!$javaRuntimeDir) { throw 'Cannot resolve the Java runtime directory.' }
# The Windows javapath launcher spawns another JVM; track the actual executable.
$javaExecutable = Join-Path ($javaRuntimeDir.Split('=',2)[1].Trim()) 'bin\java.exe'
Add-Type -AssemblyName System.Net.Http
$probeHandler = New-Object System.Net.Http.HttpClientHandler
$probeHandler.UseProxy = $false
$probeClient = New-Object System.Net.Http.HttpClient $probeHandler
$probeClient.Timeout = [TimeSpan]::FromSeconds(3)

function Run([string]$Command, [string[]]$Arguments) {
    & rtk proxy $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit code $LASTEXITCODE" }
}
function Ready([string]$Url) {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $response = $null
        try {
            $response = $probeClient.GetAsync($Url).GetAwaiter().GetResult()
            if ([int]$response.StatusCode -eq 200) { return }
        } catch { } finally { if ($null -ne $response) { $response.Dispose() } }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Service not ready: $Url; inspect $logs"
}
try {
    # These ports and this Compose project are reserved for the isolated browser suite.
    foreach ($port in @(18080,18081,15432,16379,11025,18025,4201)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
            throw "Browser smoke port $port is in use. Stop the earlier smoke environment first."
        }
    }
    Push-Location $repo
    $locationPushed = $true
    if (!$SkipBuild) { Run 'mvn' @('-f','backend/pom.xml','--batch-mode','--no-transfer-progress','-pl','auth-service,api-gateway','-am','package','-DskipTests') }
    New-Item -ItemType Directory -Path $logs,$keys -Force | Out-Null
    $composeStarted = $true
    Run 'docker' @('compose','-p',$project,'-f','scripts/smoke-compose.yml','up','-d','postgres','redis','mailhog')
    $env:JWT_ISSUER_URI = $issuer
    $env:JWT_JWK_SET_URI = "$issuer/.well-known/jwks.json"
    $env:AUTH_SERVICE_URI = $issuer
    $env:AUTH_DB_URL = 'jdbc:postgresql://127.0.0.1:15432/notifyhub'
    $env:AUTH_DB_USERNAME = 'notifyhub'
    $env:AUTH_DB_PASSWORD = 'notifyhub_local_password'
    $env:AUTH_REDIS_HOST = '127.0.0.1'
    $env:AUTH_REDIS_PORT = '16379'
    $env:AUTH_REDIS_PASSWORD = ''
    $env:AUTH_SMTP_HOST = '127.0.0.1'
    $env:AUTH_SMTP_PORT = '11025'
    $env:AUTH_SMTP_USERNAME = ''
    $env:AUTH_SMTP_PASSWORD = ''
    $env:AUTH_SMTP_AUTH = 'false'
    $env:AUTH_SMTP_STARTTLS = 'false'
    $env:AUTH_SIGNING_KEY_PATH = Join-Path $keys 'private.pem'
    $env:AUTH_CONTROL_SECRET_PATH = Join-Path $keys 'control-secret'
    $env:AUTH_REGISTRATION_ENABLED = 'true'
    $env:NOTIFYHUB_GATEWAY_URL = $gateway
    $env:NOTIFYHUB_MAILHOG_URL = 'http://127.0.0.1:18025'
    $authArgs = @('-jar','backend/auth-service/target/auth-service-0.1.0-SNAPSHOT.jar','--spring.profiles.active=local',
        '--server.port=18081','--auth.access-ttl=30s','--auth.control.trusted-proxy-addresses=127.0.0.1')
    $processes += Start-Process -FilePath $javaExecutable -ArgumentList $authArgs -WorkingDirectory $repo -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'auth.log') -RedirectStandardError (Join-Path $logs 'auth-error.log')
    Ready "$issuer/actuator/health/liveness"
    $processes += Start-Process -FilePath $javaExecutable -ArgumentList @('-jar','backend/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar',
        '--spring.profiles.active=local','--server.port=18080') -WorkingDirectory $repo -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logs 'gateway.log') -RedirectStandardError (Join-Path $logs 'gateway-error.log')
    Ready "$gateway/.well-known/jwks.json"
    Push-Location (Join-Path $repo 'frontend')
    try { Run 'node' @('node_modules/@playwright/test/cli.js','test','--config','playwright.live.config.ts','auth.live.spec.ts') } finally { Pop-Location }
} finally {
    foreach ($process in $processes) { if (!$process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue } }
    # Keep volumes for diagnosis; stop only services under our project name.
    if ($locationPushed) { Pop-Location }
    if ($composeStarted) { & rtk proxy docker compose -p $project -f (Join-Path $repo 'scripts/smoke-compose.yml') stop }
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name]) }
    $probeClient.Dispose()
}
