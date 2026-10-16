param(
    [Parameter(Mandatory=$true)][ValidateSet('auth','notification')][string]$Service,
    [Parameter(Mandatory=$true)][string]$EnvFile,
    [string]$Kubeconfig='.env.k3d/kubeconfig',
    [ValidatePattern('^notifyhub(-[a-z0-9-]+)?$')][string]$Namespace='notifyhub',
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$config=if([IO.Path]::IsPathRooted($Kubeconfig)){$Kubeconfig}else{Join-Path $root $Kubeconfig}
$Service=$Service.ToLowerInvariant()
$allowed=if($Service -eq 'auth'){@('AUTH_SMTP_HOST','AUTH_SMTP_PORT','AUTH_SMTP_USERNAME','AUTH_SMTP_PASSWORD','AUTH_MAIL_FROM','AUTH_PUBLIC_URL')}else{@('SMTP_HOST','SMTP_PORT','SMTP_USERNAME','SMTP_PASSWORD','MAIL_FROM','SMS_PROVIDER','TWILIO_ACCOUNT_SID','TWILIO_API_KEY_SID','TWILIO_API_KEY_SECRET','TWILIO_FROM','TWILIO_MESSAGING_SERVICE_SID')}
$data=@{}
foreach($line in [IO.File]::ReadAllLines((Resolve-Path -LiteralPath $EnvFile))){
    if(!$line.Trim() -or $line.TrimStart().StartsWith('#')){continue}
    $separator=$line.IndexOf('=');if($separator -lt 1){throw 'Provider file must contain KEY=value lines'}
    $key=$line.Substring(0,$separator).Trim().ToUpperInvariant()
    if($key -notin $allowed -or $data.ContainsKey($key)){throw "Unsupported or duplicate provider key: $key"}
    $data[$key]=$line.Substring($separator+1)
}
$prefix=if($Service -eq 'auth'){'AUTH_SMTP'}else{'SMTP'}
foreach($field in @("${prefix}_HOST","${prefix}_USERNAME","${prefix}_PASSWORD",$(if($Service -eq 'auth'){'AUTH_MAIL_FROM'}else{'MAIL_FROM'}))){
    if(!$data.ContainsKey($field) -or [string]::IsNullOrWhiteSpace($data[$field]) -or $data[$field] -match 'REPLACE_ME|\.invalid$'){throw "A real value is required for $field"}
}
if($data.ContainsKey("${prefix}_PORT") -and $data["${prefix}_PORT"] -ne '587'){throw 'This provider profile/egress policy uses STARTTLS port 587'}
if($Service -eq 'notification'){
    if($data.SMS_PROVIDER -notin @('simulated','twilio')){throw 'SMS_PROVIDER must explicitly be simulated or twilio'}
    if($data.SMS_PROVIDER -eq 'twilio'){
        if($data.TWILIO_ACCOUNT_SID -notmatch '^AC[0-9a-fA-F]{32}$' -or $data.TWILIO_API_KEY_SID -notmatch '^SK[0-9a-fA-F]{32}$' -or [string]::IsNullOrWhiteSpace($data.TWILIO_API_KEY_SECRET) -or $data.TWILIO_API_KEY_SECRET -match 'REPLACE_ME'){throw 'Valid Twilio API-key credentials are required'}
        $hasFrom=![string]::IsNullOrWhiteSpace($data.TWILIO_FROM);$hasService=![string]::IsNullOrWhiteSpace($data.TWILIO_MESSAGING_SERVICE_SID)
        if($hasFrom -eq $hasService -or ($hasFrom -and $data.TWILIO_FROM -notmatch '^\+[1-9]\d{7,14}$') -or ($hasService -and $data.TWILIO_MESSAGING_SERVICE_SID -notmatch '^MG[0-9a-fA-F]{32}$')){throw 'Choose exactly one valid Twilio sender or Messaging Service'}
    }
}
# Persist security flags in the Secret too, so reapplying a base Deployment cannot downgrade SMTP.
$data["${prefix}_PORT"]='587'
$data["${prefix}_AUTH"]='true'
$data["${prefix}_STARTTLS"]='true'
Write-Output "Validated provider keys for ${Service}: $($data.Keys -join ', ') (values are not printed)"
if($ValidateOnly){return}
$encoded=@{};foreach($key in $data.Keys){$encoded[$key]=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($data[$key]))}
$manifest=@{apiVersion='v1';kind='Secret';metadata=@{name="${Service}-service-secrets";namespace=$Namespace};data=$encoded}|ConvertTo-Json -Depth 10
$manifest|& kubectl --kubeconfig $config apply --server-side --field-manager=notifyhub-providers -f -
if($LASTEXITCODE -ne 0){throw 'Provider secret update failed'};$manifest=$null;$encoded=$null;$data=$null
& kubectl --kubeconfig $config -n $Namespace apply -f (Join-Path $root 'infra/providers/network-egress.yaml')
if($LASTEXITCODE -ne 0){throw 'Provider egress policy failed'}
$deployment=(& kubectl --kubeconfig $config -n $Namespace get deployment "${Service}-service" -o json)|ConvertFrom-Json
if($LASTEXITCODE -ne 0){throw 'Cannot inspect deployment profiles'}
$profiles=($deployment.spec.template.spec.containers[0].env|Where-Object name -eq SPRING_PROFILES_ACTIVE).value
if('providers' -notin $profiles.Split(',')){$profiles+=',providers'}
& kubectl --kubeconfig $config -n $Namespace set env "deployment/${Service}-service" "SPRING_PROFILES_ACTIVE=$profiles"
if($LASTEXITCODE -ne 0){throw 'Provider profile activation failed'}
& kubectl --kubeconfig $config -n $Namespace rollout restart "deployment/${Service}-service"
if($LASTEXITCODE -ne 0){throw 'Provider rollout failed'}
Write-Output 'Provider configuration applied. Inspect rollout health before creating a real campaign; no test email/SMS was sent by this script.'
