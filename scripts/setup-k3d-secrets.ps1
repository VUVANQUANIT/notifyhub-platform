param(
    [string]$Kubeconfig = '.env.k3d/kubeconfig',
    [ValidatePattern('^notifyhub(-[a-z0-9-]+)?$')][string]$Namespace = 'notifyhub',
    [string]$OpenSsl = 'C:/Program Files/Git/usr/bin/openssl.exe'
)
$ErrorActionPreference = 'Stop'
$windowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$root = Split-Path $PSScriptRoot -Parent
$directory = Join-Path $root '.env.k3d'
[IO.Directory]::CreateDirectory($directory) | Out-Null
if ($windowsHost) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls $directory /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict local secret directory permissions' }
} else { & chmod 700 $directory; if ($LASTEXITCODE -ne 0) { throw 'chmod failed' } }
function Write-Private([string]$Name, [string]$Value) {
    $path = Join-Path $directory $Name
    [IO.File]::WriteAllText($path, $Value, [Text.UTF8Encoding]::new($false))
    if (!$windowsHost) { & chmod 600 $path }
}
function New-Random {
    $bytes = [byte[]]::new(32); $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes); return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') } finally { $rng.Dispose() }
}
$runtimePath = Join-Path $directory 'runtime.json'
if (Test-Path -LiteralPath $runtimePath) { $runtime = [IO.File]::ReadAllText($runtimePath) | ConvertFrom-Json }
else {
    $runtime = [ordered]@{ postgres = (New-Random); redis = (New-Random); rabbit = (New-Random); monitoring = (New-Random); auth = (New-Random); campaign = (New-Random); notification = (New-Random); reporting = (New-Random); adminPassword = (New-Random); tenantId = [guid]::NewGuid().ToString(); userId = [guid]::NewGuid().ToString(); auditId = [guid]::NewGuid().ToString() }
    Write-Private 'runtime.json' ($runtime | ConvertTo-Json)
    $runtime = [IO.File]::ReadAllText($runtimePath) | ConvertFrom-Json
}
$key = Join-Path $directory 'private.pem'
function Invoke-OpenSsl { $ErrorActionPreference='Continue'; & $OpenSsl @args 2>&1 | Out-File -Encoding ascii -LiteralPath (Join-Path $directory 'openssl.log'); if ($LASTEXITCODE -ne 0) { throw 'OpenSSL command failed' } }
if (!(Test-Path -LiteralPath $key)) { Invoke-OpenSsl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out $key }
$control = Join-Path $directory 'control.secret'
if (!(Test-Path -LiteralPath $control)) { Write-Private 'control.secret' ((New-Random).Replace('-','+').Replace('_','/') + '=') }
$cert = Join-Path $directory 'tls.crt'; $tlsKey = Join-Path $directory 'tls.key'
if (!(Test-Path -LiteralPath $cert)) {
    Invoke-OpenSsl req -x509 -newkey rsa:3072 -sha256 -nodes -keyout $tlsKey -out $cert -days 30 -subj '/CN=notifyhub.localhost' -addext 'subjectAltName=DNS:notifyhub.localhost,DNS:localhost,IP:127.0.0.1'
}
$config = if ([IO.Path]::IsPathRooted($Kubeconfig)) { $Kubeconfig } else { Join-Path $root $Kubeconfig }
if (!(Test-Path -LiteralPath $config)) { throw 'Create the dedicated k3d kubeconfig before applying secrets' }
function Invoke-Kube { & kubectl --kubeconfig $config @args; if ($LASTEXITCODE -ne 0) { throw 'kubectl failed' } }
function Apply-Secret([string]$Name, [string[]]$Source) {
    # Keep generated JSON/secret material inside the pipeline, never write it to terminal output.
    $manifest = & kubectl --kubeconfig $config -n $Namespace create secret generic $Name @Source --dry-run=client -o json
    if ($LASTEXITCODE -ne 0) { throw "Could not prepare secret $Name" }
    $manifest | & kubectl --kubeconfig $config -n $Namespace apply -f -
    if ($LASTEXITCODE -ne 0) { throw "Could not apply secret $Name" }
    $manifest = $null
}
$namespaceManifest = & kubectl --kubeconfig $config create namespace $Namespace --dry-run=client -o json
if ($LASTEXITCODE -ne 0) { throw 'Namespace preparation failed' }
$namespaceManifest | & kubectl --kubeconfig $config apply -f -
if ($LASTEXITCODE -ne 0) { throw 'Namespace creation failed' }
Write-Private 'postgres.env' "POSTGRES_USER=postgres`nPOSTGRES_PASSWORD=$($runtime.postgres)`n"
Write-Private 'redis.env' "REDIS_PASSWORD=$($runtime.redis)`n"
Write-Private 'rabbitmq.env' "RABBITMQ_DEFAULT_USER=notifyhub`nRABBITMQ_DEFAULT_PASS=$($runtime.rabbit)`n"
Apply-Secret 'postgres-secrets' @("--from-env-file=$(Join-Path $directory 'postgres.env')")
Apply-Secret 'redis-secrets' @("--from-env-file=$(Join-Path $directory 'redis.env')")
Apply-Secret 'rabbitmq-secrets' @("--from-env-file=$(Join-Path $directory 'rabbitmq.env')")
$sql = ''
foreach ($schema in @('auth','campaign','notification','reporting')) {
    $password = $runtime.$schema
    $sql += "CREATE USER ${schema}_app PASSWORD '$password';`nCREATE SCHEMA $schema AUTHORIZATION ${schema}_app;`n"
    $prefix = $schema.ToUpperInvariant()
    $values = "${prefix}_DB_URL=jdbc:postgresql://postgres:5432/notifyhub`n${prefix}_DB_USERNAME=${schema}_app`n${prefix}_DB_PASSWORD=$password`nMONITORING_PASSWORD=$($runtime.monitoring)`n"
    if ($schema -eq 'auth') { $values += "AUTH_REDIS_PASSWORD=$($runtime.redis)`n" }
    if ($schema -in @('campaign','notification')) { $values += "RABBITMQ_USER=notifyhub`nRABBITMQ_PASSWORD=$($runtime.rabbit)`n" }
    Write-Private "${schema}-service.env" $values
    Apply-Secret "${schema}-service-secrets" @("--from-env-file=$(Join-Path $directory "${schema}-service.env")")
}
Write-Private 'init.sql' $sql
Write-Private 'api-gateway.env' "MONITORING_PASSWORD=$($runtime.monitoring)`n"
Apply-Secret 'api-gateway-secrets' @("--from-env-file=$(Join-Path $directory 'api-gateway.env')")
Apply-Secret 'postgres-init' @("--from-file=init.sql=$(Join-Path $directory 'init.sql')")
Apply-Secret 'notifyhub-auth-keys' @("--from-file=private.pem=$key", "--from-file=control.secret=$control")
Apply-Secret 'notifyhub-tls' @("--from-file=tls.crt=$cert", "--from-file=tls.key=$tlsKey")

# Initial ADMIN seed runs separately after Flyway, through the private PostgreSQL operator channel.
$salt = [byte[]]::new(16); $rng = [Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($salt); $rng.Dispose()
$derive = [Security.Cryptography.Rfc2898DeriveBytes]::new($runtime.adminPassword, $salt, 600000, [Security.Cryptography.HashAlgorithmName]::SHA256)
try { $hash = '{pbkdf2-sha256}' + [BitConverter]::ToString($salt + $derive.GetBytes(32)).Replace('-','').ToLowerInvariant() } finally { $derive.Dispose() }
$event = [ordered]@{ eventId=$runtime.auditId; eventVersion=1; action='TENANT_REGISTERED'; tenantId=$runtime.tenantId; actorId=$runtime.userId; subjectId=$runtime.userId; occurredAt=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json -Compress
$seed = @"
BEGIN;
INSERT INTO auth.tenants(id,slug,name) VALUES ('$($runtime.tenantId)','demo-team','NotifyHub Demo') ON CONFLICT(id) DO NOTHING;
INSERT INTO auth.users(id,tenant_id,email,display_name,password_hash,role) VALUES ('$($runtime.userId)','$($runtime.tenantId)','admin@example.test','Local Admin','$hash','ADMIN') ON CONFLICT(tenant_id,email) DO NOTHING;
INSERT INTO auth.audit_outbox(event_id,tenant_id,envelope,occurred_at) VALUES ('$($runtime.auditId)','$($runtime.tenantId)','$event',now()) ON CONFLICT(event_id) DO NOTHING;
COMMIT;
"@
Write-Private 'admin-seed.sql' $seed
Write-Private 'admin-login.json' (@{ tenantSlug='demo-team'; email='admin@example.test'; password=$runtime.adminPassword } | ConvertTo-Json)
Write-Output 'Secrets applied. Local credentials are in ignored .env.k3d/admin-login.json; no passwords were printed.'
