param([Parameter(Mandatory=$true)][string]$Backup,[string]$Kubeconfig='.env.k3d/kubeconfig',[string]$NodeExecutable='node')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$config=if([IO.Path]::IsPathRooted($Kubeconfig)){$Kubeconfig}else{Join-Path $root $Kubeconfig}
$private=Join-Path $root '.env.k3d'
$namespace='notifyhub-restore-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$scratchRoot=[IO.Path]::GetFullPath((Join-Path $private 'restore-work'))+[IO.Path]::DirectorySeparatorChar
$work=Join-Path $scratchRoot ([guid]::NewGuid().ToString('N'));[IO.Directory]::CreateDirectory($work)|Out-Null
$createdUid=$null
function Kube { $ErrorActionPreference='Continue'; & kubectl --kubeconfig $config @args 2>&1 | ForEach-Object { "$_" }; if($LASTEXITCODE -ne 0){throw 'Restore verification kubectl command failed'} }
try{
    & $NodeExecutable (Join-Path $PSScriptRoot 'backup-crypto.mjs') decrypt $Backup (Join-Path $work 'snapshot.zip') (Join-Path $private 'backup.key')
    if($LASTEXITCODE -ne 0){throw 'Backup authentication failed; no database has been touched'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $work 'snapshot.zip'),(Join-Path $work 'payload'))
    foreach($file in @('database.dump','private.pem','control.secret','runtime.json','manifest.json')){if(!(Test-Path -LiteralPath (Join-Path $work "payload/$file"))){throw 'Incomplete backup archive'}}
    Kube create namespace $namespace
    $createdUid=Kube get namespace $namespace -o 'jsonpath={.metadata.uid}'
    $bytes=[byte[]]::new(32);$rng=[Security.Cryptography.RandomNumberGenerator]::Create();$rng.GetBytes($bytes);$rng.Dispose()
    $password=[Convert]::ToBase64String($bytes)
    $secret=@{apiVersion='v1';kind='Secret';metadata=@{name='restore-db';namespace=$namespace};stringData=@{POSTGRES_PASSWORD=$password;POSTGRES_DB='notifyhub'}}|ConvertTo-Json -Depth 10
    $secret|& kubectl --kubeconfig $config apply -f -;if($LASTEXITCODE -ne 0){throw 'Restore secret failed'};$secret=$null
    $pod=@{apiVersion='v1';kind='Pod';metadata=@{name='postgres';namespace=$namespace};spec=@{automountServiceAccountToken=$false;containers=@(@{name='postgres';image='postgres:16-alpine';envFrom=@(@{secretRef=@{name='restore-db'}});resources=@{requests=@{memory='64Mi';cpu='50m'};limits=@{memory='384Mi';cpu='1'}};readinessProbe=@{exec=@{command=@('sh','-c','pg_isready -U postgres -d notifyhub')};initialDelaySeconds=3;periodSeconds=2}})}}|ConvertTo-Json -Depth 20
    $pod|& kubectl --kubeconfig $config apply -f -;if($LASTEXITCODE -ne 0){throw 'Restore pod failed'}
    Kube -n $namespace wait --for=condition=Ready pod/postgres --timeout=120s
    Push-Location $root
    try { Kube -n $namespace cp ('.env.k3d/restore-work/'+[IO.Path]::GetFileName($work)+'/payload/database.dump') "${namespace}/postgres:/tmp/database.dump" } finally { Pop-Location }
    Kube -n $namespace exec postgres '--' pg_restore -U postgres -d notifyhub --no-owner --no-privileges --exit-on-error /tmp/database.dump
    Kube -n $namespace exec postgres '--' psql -U postgres -d notifyhub -v ON_ERROR_STOP=1 -c 'SELECT count(*) AS restored_users FROM auth.users; SELECT count(*) AS restored_campaigns FROM campaign.campaigns; SELECT count(*) AS restored_deliveries FROM notification.deliveries; SELECT count(*) AS restored_reports FROM reporting.campaign_reports;'
    Write-Output 'Authenticated archive, database restore and all four service-schema queries passed. Original namespace was not modified.'
}finally{
    if($createdUid){$currentUid=& kubectl --kubeconfig $config get namespace $namespace -o 'jsonpath={.metadata.uid}';if($LASTEXITCODE -eq 0 -and $currentUid -eq $createdUid -and $namespace -match '^notifyhub-restore-[a-f0-9]{12}$'){Kube delete namespace $namespace --wait=false}}
    $resolved=[IO.Path]::GetFullPath($work)
    if(!$resolved.StartsWith($scratchRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^[a-f0-9]{32}$'){throw 'Unsafe restore scratch cleanup target'}
    if(Test-Path -LiteralPath $resolved){if((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing reparse-point cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}
}
