param([string]$Kubeconfig='.env.k3d/kubeconfig')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$config=if([IO.Path]::IsPathRooted($Kubeconfig)){$Kubeconfig}else{Join-Path $root $Kubeconfig}
Get-Command kubectl -CommandType Application -ErrorAction Stop|Out-Null
function Connects([string]$Destination,[int]$Port){
    $ErrorActionPreference='Continue'
    & kubectl --kubeconfig $config -n notifyhub exec deployment/frontend -- nc -z -w 2 $Destination $Port 2>&1 | Out-Null
    return $LASTEXITCODE -eq 0
}
$postgres=(& kubectl --kubeconfig $config -n notifyhub get pods -l app=postgres -o json)|ConvertFrom-Json
if($LASTEXITCODE -ne 0 -or !$postgres.items[0].status.podIP){throw 'Cannot locate the local PostgreSQL pod'}
$targets=@(@('postgres',5432),@($postgres.items[0].status.podIP,5432),@('redis',6379),@('rabbitmq',5672),@('kafka',9092),@('auth-service',8081))
# CNI updates are asynchronous. Require an allowed positive control as well as every forbidden path.
$verified=$false
for($attempt=0;$attempt -lt 10;$attempt++){
    if(Connects 'api-gateway' 8080){
        $unexpected=@($targets|Where-Object { Connects $_[0] $_[1] })
        if($unexpected.Count -eq 0){$verified=$true;break}
    }
    Start-Sleep -Seconds 1
}
if(!$verified){throw 'Network isolation failed: Gateway must be reachable; frontend must not reach DB/Redis/brokers/Auth directly'}
Write-Output 'Network isolation passed: frontend -> Gateway allowed; DB service/pod, Redis, RabbitMQ, Kafka and Auth denied.'
function Can-ReadCampaign([string]$DatabaseUser){
    $ErrorActionPreference='Continue'
    & kubectl --kubeconfig $config -n notifyhub exec deployment/postgres -- psql -U $DatabaseUser -d notifyhub -v ON_ERROR_STOP=1 -c 'SELECT count(*) FROM campaign.campaigns;' 2>&1|Out-Null
    return $LASTEXITCODE -eq 0
}
if((Can-ReadCampaign 'auth_app') -or !(Can-ReadCampaign 'campaign_app')){throw 'Database role isolation failed'}
Write-Output 'Database role isolation passed: Campaign role reads its schema; Auth role is denied.'
