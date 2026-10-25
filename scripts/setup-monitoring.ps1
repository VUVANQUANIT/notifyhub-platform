param([switch]$Kafka)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$configDir = Join-Path $projectRoot '.env.monitoring'
New-Item -ItemType Directory -Path $configDir -Force | Out-Null
$passwordFile = Join-Path $configDir 'MONITORING_PASSWORD'
if (!(Test-Path -LiteralPath $passwordFile)) {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String($bytes))
}
if ([string]::IsNullOrWhiteSpace([IO.File]::ReadAllText($passwordFile))) { throw 'Monitoring password must not be empty' }
[IO.File]::WriteAllText((Join-Path $configDir 'MONITORING_USERNAME'), 'notifyhub-monitoring')
$targets = if ($Kafka) { '[{"targets":["kafka-exporter:9308"],"labels":{"service":"kafka-exporter"}}]' } else { '[]' }
[IO.File]::WriteAllText((Join-Path $configDir 'kafka-targets.json'), $targets)
Write-Output 'Monitoring credentials ready in .env.monitoring (ignored by Git).'
Write-Output "Kafka monitoring enabled: $([bool]$Kafka)"
