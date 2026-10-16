param([switch]$SkipBuild, [string]$NodeExecutable = 'node', [string]$JavaExecutable = '')
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$project = 'notifyhub-members-browser'
$owned = @()
$started = $false
$logs = Join-Path $repo 'frontend\test-results\member-services'
$keys = Join-Path $repo '.env.members-browser-smoke'
if (!$JavaExecutable) {
    $readPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $settings = & rtk proxy java -XshowSettings:properties -version 2>&1
    $ErrorActionPreference = $readPreference
    $runtime = [string]($settings | Where-Object { [string]$_ -match '^\s*java.home\s*=' } | Select-Object -First 1)
    if (!$runtime) { throw 'Cannot resolve the Java runtime directory.' }
    $JavaExecutable = Join-Path ($runtime.Split('=',2)[1].Trim()) 'bin\java.exe'
}
$envNames = @('JWT_ISSUER_URI','JWT_JWK_SET_URI','AUTH_SERVICE_URI','AUTH_REDIS_HOST','AUTH_REDIS_PORT','AUTH_REDIS_PASSWORD','AUTH_SMTP_HOST','AUTH_SMTP_PORT','AUTH_SMTP_USERNAME','AUTH_SMTP_PASSWORD','AUTH_SMTP_AUTH','AUTH_SMTP_STARTTLS','AUTH_SIGNING_KEY_PATH','AUTH_CONTROL_SECRET_PATH','NOTIFYHUB_GATEWAY_URL','SMOKE_REDIS_PORT','SMOKE_POSTGRES_PORT','SMOKE_SMTP_PORT','SMOKE_MAILHOG_PORT')
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
    $JavaExecutable = (Resolve-Path -LiteralPath $JavaExecutable).ProviderPath
    foreach ($port in @(38080,38081,35432,36379,31025,38025,4201)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Member smoke port $port is already in use." }
    }
    if (!$SkipBuild) {
        Push-Location (Join-Path $repo 'backend')
        try { Run '.\mvnw.cmd' @('--batch-mode','--no-transfer-progress','-pl','auth-service,api-gateway','-am','package','-DskipTests') } finally { Pop-Location }
    }
    New-Item -ItemType Directory -Path $logs,$keys -Force | Out-Null
    $env:SMOKE_POSTGRES_PORT = '35432'; $env:SMOKE_REDIS_PORT = '36379'; $env:SMOKE_SMTP_PORT = '31025'; $env:SMOKE_MAILHOG_PORT = '38025'
    $started = $true
    Run 'docker' @('compose','-p',$project,'-f','scripts/smoke-compose.yml','up','-d','postgres','redis','mailhog')
    $env:JWT_ISSUER_URI = 'http://127.0.0.1:38081'; $env:JWT_JWK_SET_URI = "$env:JWT_ISSUER_URI/.well-known/jwks.json"; $env:AUTH_SERVICE_URI = $env:JWT_ISSUER_URI
    $env:AUTH_REDIS_HOST = '127.0.0.1'; $env:AUTH_REDIS_PORT = '36379'; $env:AUTH_REDIS_PASSWORD = ''
    $env:AUTH_SMTP_HOST = '127.0.0.1'; $env:AUTH_SMTP_PORT = '31025'; $env:AUTH_SMTP_USERNAME = ''; $env:AUTH_SMTP_PASSWORD = ''; $env:AUTH_SMTP_AUTH = 'false'; $env:AUTH_SMTP_STARTTLS = 'false'
    $env:AUTH_SIGNING_KEY_PATH = Join-Path $keys 'private.pem'; $env:AUTH_CONTROL_SECRET_PATH = Join-Path $keys 'control-secret'
    $env:NOTIFYHUB_GATEWAY_URL = 'http://127.0.0.1:38080'
    foreach ($entry in @(@('auth-service',38081),@('api-gateway',38080))) {
        $module = $entry[0]; $port = $entry[1]; $jar = "backend/$module/target/$module-0.1.0-SNAPSHOT.jar"
        $arguments = @('-Xmx256m','-jar',$jar,'--spring.profiles.active=local',"--server.port=$port")
        if ($module -eq 'auth-service') { $arguments += @('--spring.datasource.url=jdbc:postgresql://127.0.0.1:35432/notifyhub','--spring.datasource.username=notifyhub','--spring.datasource.password=notifyhub_local_password','--auth.registration-enabled=true') }
        $process = Start-Process -FilePath $JavaExecutable -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs "$module.log") -RedirectStandardError (Join-Path $logs "$module-error.log")
        $owned += [pscustomobject]@{Id=$process.Id;Jar=$jar;Port=$port}
    }
    foreach ($service in $owned) { Ready "http://127.0.0.1:$($service.Port)/actuator/health/liveness" }
    Ready 'http://127.0.0.1:38080/.well-known/jwks.json'
    Push-Location (Join-Path $repo 'frontend')
    try { Run $NodeExecutable @('node_modules/@playwright/test/cli.js','test','--config','playwright.live.config.ts','members.live.spec.ts') } finally { Pop-Location }
} finally {
    foreach ($service in $owned) {
        $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($service.Id)"
        if ($null -ne $current -and $current.ExecutablePath -eq $JavaExecutable -and $current.CommandLine.Contains($service.Jar) -and $current.CommandLine.Contains("--server.port=$($service.Port)")) { Stop-Process -Id $service.Id -Force }
    }
    if ($started) { & rtk proxy docker compose -p $project -f scripts/smoke-compose.yml down }
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name]) }
    $probe.Dispose(); Pop-Location
}
