param(
    [string]$HostUrl = 'http://127.0.0.1',
    [string]$PrometheusUrl = 'http://127.0.0.1:9090',
    [int]$TimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$configDir = Join-Path $projectRoot '.env.monitoring'
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(10)
$username = [IO.File]::ReadAllText((Join-Path $configDir 'MONITORING_USERNAME')).Trim()
$password = [IO.File]::ReadAllText((Join-Path $configDir 'MONITORING_PASSWORD')).Trim()
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${username}:$password"))
try {
    foreach ($port in 8080..8084) {
        $url = "${HostUrl}:$port/actuator/prometheus"
        $anonymous = $client.GetAsync($url).GetAwaiter().GetResult()
        try { if ([int]$anonymous.StatusCode -ne 401) { throw "Anonymous scrape must return 401 on port $port" } }
        finally { $anonymous.Dispose() }
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $url)
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Basic', $basic)
        try {
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            try {
                $response.EnsureSuccessStatusCode() | Out-Null
                $metrics = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                if ($metrics -notmatch 'jvm_memory_used_bytes') { throw "JVM metrics missing on port $port" }
                if ($port -ne 8080 -and $metrics -notmatch 'notifyhub_monitoring_database_up\{[^}]*\}\s+1(?:\.0)?\s') {
                    throw "Database snapshot unhealthy on port $port"
                }
            } finally { $response.Dispose() }
        } finally { $request.Dispose() }
        Write-Output "PASS: authenticated metrics on $port"
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $targets = (Invoke-RestMethod "$PrometheusUrl/api/v1/targets").data.activeTargets
        $services = @($targets | Where-Object { $_.labels.job -eq 'notifyhub-services' })
        $brokerTargets = @($targets | Where-Object { $_.labels.job -in 'rabbitmq', 'kafka' })
        $unhealthy = @($services + $brokerTargets | Where-Object { $_.health -ne 'up' })
        if ($services.Count -eq 5 -and $brokerTargets.Count -ge 1 -and $unhealthy.Count -eq 0) { break }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($services.Count -ne 5 -or $brokerTargets.Count -lt 1 -or $unhealthy.Count -gt 0) { throw 'Prometheus targets are missing or unhealthy; inspect /targets' }
    $groups = (Invoke-RestMethod "$PrometheusUrl/api/v1/rules").data.groups
    $rules = @($groups | Where-Object { $_.name -eq 'notifyhub' } | ForEach-Object { $_.rules })
    if ($rules.Count -ne 11 -or @($rules | Where-Object { $_.health -ne 'ok' }).Count -gt 0) { throw 'Expected 11 healthy NotifyHub alert rules' }
    Write-Output "PASS: five service targets, $($brokerTargets.Count) broker/exporter targets and 11 alert rules"
} finally { $client.Dispose() }
