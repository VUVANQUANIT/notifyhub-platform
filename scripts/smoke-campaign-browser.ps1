param([switch]$SkipBuild, [string]$NodeExecutable = 'node', [string]$JavaExecutable = '')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$project = 'notifyhub-campaign-browser'
$javaReadPreference = $ErrorActionPreference
if (!$JavaExecutable) {
    $ErrorActionPreference = 'Continue'
    $javaSettings = & rtk proxy java -XshowSettings:properties -version 2>&1
    $ErrorActionPreference = $javaReadPreference
    $runtime = [string]($javaSettings | Where-Object { [string]$_ -match '^\s*java.home\s*=' } | Select-Object -First 1)
    if (!$runtime) { throw 'Cannot resolve Java runtime directory.' }
    $JavaExecutable = Join-Path ($runtime.Split('=',2)[1].Trim()) 'bin\java.exe'
}
$owned = @()
$started = $false
$logs = Join-Path $repo 'frontend\test-results\campaign-services'
$keys = Join-Path $repo '.env.campaign-browser-smoke'
$envNames = @('JWT_ISSUER_URI','JWT_JWK_SET_URI','AUTH_SERVICE_URI','CAMPAIGN_SERVICE_URI','REPORTING_SERVICE_URI','AUTH_REDIS_HOST','AUTH_REDIS_PORT','AUTH_REDIS_PASSWORD','AUTH_SMTP_HOST','AUTH_SMTP_PORT','AUTH_SMTP_USERNAME','AUTH_SMTP_PASSWORD','AUTH_SMTP_AUTH','AUTH_SMTP_STARTTLS','AUTH_SIGNING_KEY_PATH','AUTH_CONTROL_SECRET_PATH','KAFKA_BOOTSTRAP_SERVERS','RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_USER','RABBITMQ_PASSWORD','SMTP_HOST','SMTP_PORT','NOTIFYHUB_GATEWAY_URL','SMOKE_REDIS_PORT','SMOKE_POSTGRES_PORT','SMOKE_RABBITMQ_PORT','SMOKE_SMTP_PORT','SMOKE_MAILHOG_PORT','SMOKE_KAFKA_PORT')
$savedEnv = @{}
foreach ($name in $envNames) { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name) }
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$probe = New-Object System.Net.Http.HttpClient $handler
$probe.Timeout = [TimeSpan]::FromSeconds(3)
function Run([string]$Command, [string[]]$Arguments) {
    & rtk proxy $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit code $LASTEXITCODE" }
}
function Ready([string]$Url) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        $response = $null
        try { $response = $probe.GetAsync($Url).GetAwaiter().GetResult(); if ([int]$response.StatusCode -eq 200) { return } } catch { }
        finally { if ($null -ne $response) { $response.Dispose() } }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Service not ready: $Url; inspect $logs"
}
Push-Location $repo
try {
    if (!(Test-Path -LiteralPath $javaExecutable)) { throw 'Java 21 executable missing. Configure $javaExecutable for this workstation.' }
    $JavaExecutable = (Resolve-Path -LiteralPath $JavaExecutable).ProviderPath
    foreach ($port in @(28080,28081,28082,28083,28084,25432,26379,25673,29093,21025,28025,4201)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Smoke port $port is already in use." }
    }
    if (!$SkipBuild) {
        Push-Location (Join-Path $repo 'backend')
        try { Run '.\mvnw.cmd' @('--batch-mode','--no-transfer-progress','package','-DskipTests') } finally { Pop-Location }
    }
    New-Item -ItemType Directory -Path $logs,$keys -Force | Out-Null
    $env:SMOKE_POSTGRES_PORT = '25432'; $env:SMOKE_REDIS_PORT = '26379'; $env:SMOKE_RABBITMQ_PORT = '25673'; $env:SMOKE_KAFKA_PORT = '29093'; $env:SMOKE_SMTP_PORT = '21025'; $env:SMOKE_MAILHOG_PORT = '28025'
    $started = $true
    Run 'docker' @('compose','-p',$project,'-f','scripts/smoke-compose.yml','up','-d')
    $env:JWT_ISSUER_URI = 'http://127.0.0.1:28081'; $env:JWT_JWK_SET_URI = "$env:JWT_ISSUER_URI/.well-known/jwks.json"
    $env:AUTH_SERVICE_URI = $env:JWT_ISSUER_URI; $env:CAMPAIGN_SERVICE_URI = 'http://127.0.0.1:28082'; $env:REPORTING_SERVICE_URI = 'http://127.0.0.1:28084'
    $env:AUTH_REDIS_HOST = '127.0.0.1'; $env:AUTH_REDIS_PORT = '26379'; $env:AUTH_SMTP_HOST = '127.0.0.1'; $env:AUTH_SMTP_PORT = '21025'
    $env:AUTH_REDIS_PASSWORD = ''; $env:AUTH_SMTP_USERNAME = ''; $env:AUTH_SMTP_PASSWORD = ''; $env:AUTH_SMTP_AUTH = 'false'; $env:AUTH_SMTP_STARTTLS = 'false'
    $env:AUTH_SIGNING_KEY_PATH = Join-Path $keys 'private.pem'; $env:AUTH_CONTROL_SECRET_PATH = Join-Path $keys 'control-secret'
    $env:KAFKA_BOOTSTRAP_SERVERS = '127.0.0.1:29093'; $env:RABBITMQ_HOST = '127.0.0.1'; $env:RABBITMQ_PORT = '25673'; $env:RABBITMQ_USER = 'notifyhub'; $env:RABBITMQ_PASSWORD = 'notifyhub_local_password'
    $env:SMTP_HOST = '127.0.0.1'; $env:SMTP_PORT = '21025'; $env:NOTIFYHUB_GATEWAY_URL = 'http://127.0.0.1:28080'
    foreach ($entry in @(@('auth-service',28081,'local'),@('api-gateway',28080,'local'),@('campaign-service',28082,'authenticated-local,messaging'),@('notification-service',28083,'local,worker,events'),@('reporting-service',28084,'authenticated-local,messaging'))) {
        $module = $entry[0]; $port = $entry[1]; $profile = $entry[2]; $jar = "backend/$module/target/$module-0.1.0-SNAPSHOT.jar"
        $arguments = @('-Xmx256m','-jar',$jar,"--spring.profiles.active=$profile","--server.port=$port")
        if ($module -ne 'api-gateway') { $arguments += @('--spring.datasource.url=jdbc:postgresql://127.0.0.1:25432/notifyhub','--spring.datasource.username=notifyhub','--spring.datasource.password=notifyhub_local_password') }
        if ($module -eq 'auth-service') { $arguments += '--auth.registration-enabled=true' }
        $process = Start-Process -FilePath $javaExecutable -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs "$module.log") -RedirectStandardError (Join-Path $logs "$module-error.log")
        $owned += [pscustomobject]@{Id=$process.Id;Jar=$jar;Port=$port}
    }
    foreach ($service in $owned) { Ready "http://127.0.0.1:$($service.Port)/actuator/health/liveness" }
    Ready 'http://127.0.0.1:28080/.well-known/jwks.json'
    Push-Location (Join-Path $repo 'frontend')
    try { Run $NodeExecutable @('node_modules/@playwright/test/cli.js','test','--config','playwright.live.config.ts','campaign.live.spec.ts') } finally { Pop-Location }
} finally {
    foreach ($service in $owned) {
        $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($service.Id)"
        if ($null -ne $current -and $current.ExecutablePath -eq $javaExecutable -and $current.CommandLine.Contains($service.Jar) -and $current.CommandLine.Contains("--server.port=$($service.Port)")) { Stop-Process -Id $service.Id -Force }
    }
    if ($started) { & rtk proxy docker compose -p $project -f scripts/smoke-compose.yml down }
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name]) }
    $probe.Dispose()
    Pop-Location
}
