param(
    [string]$CampaignUrl = 'http://127.0.0.1:8082',
    [string]$ReportingUrl = 'http://127.0.0.1:8084',
    [string]$MailHogUrl = 'http://127.0.0.1:8025',
    [int]$TimeoutSeconds = 90,
    [switch]$ExpectEmailFailure
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(10)
$tenantId = [guid]::NewGuid().ToString()
$runId = [guid]::NewGuid().ToString()
$client.DefaultRequestHeaders.Add('X-Tenant-Id', $tenantId)
$client.DefaultRequestHeaders.Add('X-User-Id', [guid]::NewGuid().ToString())

function Send-Json([string]$Method, [string]$Url, [object]$Body, [string]$Key) {
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($Method)), $Url
    if ($null -ne $Body) {
        $request.Content = New-Object System.Net.Http.StringContent ($Body | ConvertTo-Json -Compress), ([Text.Encoding]::UTF8), 'application/json'
    }
    if ($Key) { $request.Headers.Add('Idempotency-Key', $Key) }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $response.EnsureSuccessStatusCode() | Out-Null
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ($raw) { return $raw | ConvertFrom-Json }
    } finally { $request.Dispose() }
}

try {
    foreach ($channel in @('EMAIL', 'SMS')) {
        $expectedStatus = if ($channel -eq 'EMAIL' -and $ExpectEmailFailure) { 'FAILED' } else { 'COMPLETED' }
        $expectedSent = if ($expectedStatus -eq 'FAILED') { 0 } else { 1 }
        $expectedFailed = if ($expectedStatus -eq 'FAILED') { 1 } else { 0 }
        $subject = if ($channel -eq 'EMAIL') { "NotifyHub smoke $runId" } else { $null }
        $campaign = Send-Json 'POST' "$CampaignUrl/api/campaigns" @{
            name = "Smoke $channel $runId"; channel = $channel; subject = $subject; body = "Hello smoke $runId"; scheduledAt = $null
        } ([guid]::NewGuid().ToString())
        $csv = if ($channel -eq 'EMAIL') { "email,name`nsmoke@example.com,Smoke`n" } else { "phoneNumber,name`n+84901234567,Smoke`n" }
        $multipart = New-Object System.Net.Http.MultipartFormDataContent
        $file = New-Object System.Net.Http.ByteArrayContent -ArgumentList (,([Text.Encoding]::UTF8.GetBytes($csv)))
        $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('text/csv')
        $multipart.Add($file, 'file', 'recipients.csv')
        try {
            $response = $client.PostAsync("$CampaignUrl/api/campaigns/$($campaign.id)/imports", $multipart).GetAwaiter().GetResult()
            $response.EnsureSuccessStatusCode() | Out-Null
        } finally { $multipart.Dispose() }
        Send-Json 'POST' "$CampaignUrl/api/campaigns/$($campaign.id)/start" $null ([guid]::NewGuid().ToString()) | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        $done = $false
        do {
            $state = Send-Json 'GET' "$CampaignUrl/api/campaigns/$($campaign.id)" $null $null
            $report = $null
            try { $report = Send-Json 'GET' "$ReportingUrl/api/reports/campaigns/$($campaign.id)" $null $null } catch {}
            if ($state.status -eq 'FAILED' -and $expectedStatus -eq 'COMPLETED') { throw "Smoke campaign failed: $($campaign.id)" }
            if ($state.status -eq $expectedStatus -and $null -ne $report -and $report.status -eq $expectedStatus -and $report.sent -eq $expectedSent -and $report.failed -eq $expectedFailed -and $report.pending -eq 0) {
                $done = $true
                break
            }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        if (!$done) { throw "Timed out waiting for $channel campaign and report" }
        $deliveries = Send-Json 'GET' "$ReportingUrl/api/reports/campaigns/$($campaign.id)/deliveries" $null $null
        if ($deliveries.totalElements -ne 1 -or $deliveries.items[0].channel -ne $channel) { throw 'Unexpected delivery report' }
        if ($expectedStatus -eq 'FAILED' -and $deliveries.items[0].failureCode -ne 'SmtpUnavailable') { throw 'Unexpected SMTP failure code' }
        $client.DefaultRequestHeaders.Remove('X-Tenant-Id') | Out-Null
        $client.DefaultRequestHeaders.Add('X-Tenant-Id', [guid]::NewGuid().ToString())
        $other = $client.GetAsync("$ReportingUrl/api/reports/campaigns/$($campaign.id)").GetAwaiter().GetResult()
        if ([int]$other.StatusCode -ne 404) { throw 'Cross-tenant report was visible' }
        $client.DefaultRequestHeaders.Remove('X-Tenant-Id') | Out-Null
        $client.DefaultRequestHeaders.Add('X-Tenant-Id', $tenantId)
        Write-Output "$channel PASS: campaign=$($campaign.id), status=$expectedStatus, sent=$expectedSent, failed=$expectedFailed, pending=0, attempts=$($report.attempts), tenant isolation=404"
    }
    if (!$ExpectEmailFailure) {
        $mail = Invoke-RestMethod -Uri "$MailHogUrl/api/v2/search?kind=containing&query=$runId"
        if ($mail.total -lt 1) { throw 'Smoke email missing in MailHog' }
        Write-Output "MailHog PASS: run=$runId"
    }
} finally { $client.Dispose() }
