param(
    [string]$GatewayUrl = 'http://127.0.0.1:8080',
    [string]$MailHogUrl = 'http://127.0.0.1:8025',
    [int]$TimeoutSeconds = 30
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(10)
$run = [guid]::NewGuid().ToString('N')
$slug = "recovery-$run"
$old = 'old smoke passphrase for NotifyHub'
$new = 'new smoke passphrase for NotifyHub'

function Request([string]$Path, [object]$Body, [string]$Token, [int]$Expected, [string]$Label) {
    $request = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::Post), "$GatewayUrl$Path"
    $request.Content = New-Object System.Net.Http.StringContent ($Body | ConvertTo-Json -Compress), ([Text.Encoding]::UTF8), 'application/json'
    $request.Headers.Add('X-Forwarded-For', [guid]::NewGuid().ToString())
    if ($Token) { $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token) }
    $response = $null
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne $Expected) { throw "$Label expected $Expected, received $([int]$response.StatusCode)" }
        if ($Expected -eq 429 -and !$response.Headers.Contains('Retry-After')) { throw 'Missing Retry-After' }
        Write-Output "$Label PASS ($Expected)" | Out-Host
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ($raw) { return $raw | ConvertFrom-Json }
    } finally { if ($null -ne $response) { $response.Dispose() }; $request.Dispose() }
}

function Wait-Mail([string]$Challenge, [string]$Subject) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $found = Invoke-RestMethod -Uri "$MailHogUrl/api/v2/search?kind=containing&query=$Challenge"
        foreach ($mail in $found.items) {
            if ($mail.Content.Headers.Subject[0] -eq $Subject) { return $mail.Content.Body }
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Subject"
}

try {
    $tokens = Request '/api/auth/register' @{tenantSlug=$slug;tenantName='Recovery Smoke';email='admin@example.com';displayName='Admin';password=$old} $null 200 'Register'
    $missing = Request '/api/auth/password/forgot' @{tenantSlug=$slug;email='missing@example.com'} $null 202 'Unknown account response'
    $known = Request '/api/auth/password/forgot' @{tenantSlug=$slug;email='admin@example.com'} $null 202 'Known account response'
    if ($missing.message -ne $known.message -or $null -ne $known.code) { throw 'Recovery response exposed account or OTP' }
    Write-Output 'Generic response PASS'
    Request '/api/auth/password/forgot' @{tenantSlug=$slug.ToUpperInvariant();email=' ADMIN@example.com '} $null 429 'Normalized resend cooldown' | Out-Null
    $message = Wait-Mail $known.challengeId 'NotifyHub password recovery'
    $match = [regex]::Match($message, 'recovery code is: ([0-9]{8})')
    if (!$match.Success) { throw 'OTP email did not contain an eight-digit code' }
    $code = $match.Groups[1].Value
    Write-Output 'MailHog OTP delivery PASS'
    $wrong = if ($code -eq '00000000') { '99999999' } else { '00000000' }
    Request '/api/auth/password/reset' @{challengeId=$known.challengeId;code=$wrong;password=$new} $null 401 'Wrong OTP rejected' | Out-Null
    Request '/api/auth/password/reset' @{challengeId=$known.challengeId;code=$code;password=$new} $null 204 'Password reset'
    Request '/api/auth/login' @{tenantSlug=$slug;email='admin@example.com';password=$old} $null 401 'Old password rejected' | Out-Null
    Request '/api/auth/login' @{tenantSlug=$slug;email='admin@example.com';password=$new} $null 200 'New password login' | Out-Null
    Request '/api/auth/refresh' @{refreshToken=$tokens.refreshToken} $null 401 'Existing refresh session revoked' | Out-Null
    Request '/api/auth/password/reset' @{challengeId=$known.challengeId;code=$code;password=$old} $null 401 'OTP replay rejected' | Out-Null
    $notice = Wait-Mail $known.challengeId 'NotifyHub password changed'
    if ($notice.Contains($new) -or $notice.Contains($old) -or $notice.Contains($code)) { throw 'Security notice exposed a secret' }
    Write-Output 'Password-change security notice PASS'
    Request '/api/auth/users' @{email='member@example.com';displayName='Member';role='VIEWER';password=$old} $tokens.accessToken 201 'Create recovery member' | Out-Null
    $member = Request '/api/auth/password/forgot' @{tenantSlug=$slug;email='member@example.com'} $null 202 'Member OTP request'
    $memberMail = Wait-Mail $member.challengeId 'NotifyHub password recovery'
    $memberCode = [regex]::Match($memberMail, 'recovery code is: ([0-9]{8})').Groups[1].Value
    $bad = if ($memberCode -eq '00000000') { '99999999' } else { '00000000' }
    for ($i=1; $i -le 5; $i++) { Request '/api/auth/password/reset' @{challengeId=$member.challengeId;code=$bad;password=$new} $null 401 "Guess $i rejected" | Out-Null }
    Request '/api/auth/password/reset' @{challengeId=$member.challengeId;code=$memberCode;password=$new} $null 401 'Guess limit destroys proof' | Out-Null
    Write-Output 'Recovery smoke PASS: Gateway, Redis limits, MailHog OTP/notice, password change, refresh revocation, replay and guess limit; secrets omitted.'
} finally { $client.Dispose() }
