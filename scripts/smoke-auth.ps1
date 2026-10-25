param(
    [string]$GatewayUrl = 'http://127.0.0.1:8080',
    [string]$CampaignUrl = 'http://127.0.0.1:8082',
    [string]$ReportingUrl = 'http://127.0.0.1:8084'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(15)
$run = [guid]::NewGuid().ToString('N')
$password = 'local smoke passphrase for NotifyHub'

function Request([string]$Method, [string]$Url, [object]$Body, [string]$Token, [int]$Expected, [string]$Label) {
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($Method)), $Url
    $request.Headers.Add('X-Tenant-Id', [guid]::NewGuid().ToString())
    $request.Headers.Add('X-User-Id', [guid]::NewGuid().ToString())
    if ($Token) { $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token) }
    if ($Method -eq 'POST' -and $Url -match '/api/campaigns$') { $request.Headers.Add('Idempotency-Key', [guid]::NewGuid().ToString()) }
    if ($null -ne $Body) { $request.Content = New-Object System.Net.Http.StringContent ($Body | ConvertTo-Json -Compress), ([Text.Encoding]::UTF8), 'application/json' }
    $response = $null
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne $Expected) { throw "$Label expected $Expected, received $([int]$response.StatusCode)" }
        Write-Output "$Label PASS ($Expected)" | Out-Host
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ($raw) { return $raw | ConvertFrom-Json }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

try {
    Request 'GET' "$GatewayUrl/api/campaigns" $null $null 401 'Anonymous gateway' | Out-Null
    Request 'GET' "$CampaignUrl/api/campaigns" $null $null 401 'Anonymous direct Campaign' | Out-Null
    Request 'GET' "$ReportingUrl/api/reports/summary" $null $null 401 'Anonymous direct Reporting' | Out-Null
    $one = Request 'POST' "$GatewayUrl/api/auth/register" @{tenantSlug="auth-$run";tenantName='Smoke One';email='admin@example.com';displayName='Admin';password=$password} $null 200 'Register tenant one'
    $two = Request 'POST' "$GatewayUrl/api/auth/register" @{tenantSlug="other-$run";tenantName='Smoke Two';email='admin@example.com';displayName='Admin';password=$password} $null 200 'Register tenant two'
    Request 'GET' "$GatewayUrl/api/auth/me" $null $one.accessToken 200 'JWT identity' | Out-Null
    Request 'GET' "$GatewayUrl/.well-known/jwks.json" $null $null 200 'Public JWKS via gateway' | Out-Null
    $viewer = Request 'POST' "$GatewayUrl/api/auth/users" @{email='viewer@example.com';displayName='Viewer';password=$password;role='VIEWER'} $one.accessToken 201 'Create viewer'
    $viewerTokens = Request 'POST' "$GatewayUrl/api/auth/login" @{tenantSlug="auth-$run";email=$viewer.email;password=$password} $null 200 'Viewer login'
    $campaignBody = @{name="Auth smoke $run";channel='SMS';subject=$null;body='Hello JWT';scheduledAt=$null}
    $campaign = Request 'POST' "$GatewayUrl/api/campaigns" $campaignBody $one.accessToken 201 'Admin create campaign'
    Request 'GET' "$GatewayUrl/api/campaigns/$($campaign.id)" $null $viewerTokens.accessToken 200 'Viewer reads campaign' | Out-Null
    Request 'POST' "$GatewayUrl/api/campaigns" $campaignBody $viewerTokens.accessToken 403 'Viewer cannot write at gateway' | Out-Null
    Request 'POST' "$CampaignUrl/api/campaigns" $campaignBody $viewerTokens.accessToken 403 'Viewer cannot write direct' | Out-Null
    Request 'GET' "$GatewayUrl/api/campaigns/$($campaign.id)" $null $two.accessToken 404 'Cross-tenant campaign at gateway' | Out-Null
    Request 'GET' "$CampaignUrl/api/campaigns/$($campaign.id)" $null $two.accessToken 404 'Cross-tenant campaign direct' | Out-Null
    Request 'GET' "$GatewayUrl/api/reports/summary" $null $viewerTokens.accessToken 200 'Viewer reads Reporting' | Out-Null
    Request 'GET' "$ReportingUrl/api/reports/summary" $null $viewerTokens.accessToken 200 'Reporting verifies real issuer direct' | Out-Null
    Request 'GET' "$GatewayUrl/api/auth/users" $null $viewerTokens.accessToken 403 'Viewer cannot list users' | Out-Null
    $rotated = Request 'POST' "$GatewayUrl/api/auth/refresh" @{refreshToken=$one.refreshToken} $null 200 'Refresh rotation'
    Request 'POST' "$GatewayUrl/api/auth/refresh" @{refreshToken=$one.refreshToken} $null 401 'Replay detected' | Out-Null
    Request 'POST' "$GatewayUrl/api/auth/refresh" @{refreshToken=$rotated.refreshToken} $null 401 'Replay revokes successor' | Out-Null
    Request 'POST' "$GatewayUrl/api/auth/logout" @{refreshToken=$two.refreshToken} $null 204 'Logout'
    Request 'POST' "$GatewayUrl/api/auth/refresh" @{refreshToken=$two.refreshToken} $null 401 'Logged-out session rejected' | Out-Null
    Write-Output 'Auth smoke PASS: real JWT/JWKS, roles, direct-service protection, tenant isolation, rotation/replay/logout; credentials omitted.'
} finally { $client.Dispose() }
