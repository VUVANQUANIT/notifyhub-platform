param([string]$Kubeconfig='.env.k3d/kubeconfig',[string]$NodeExecutable='node')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$config=if([IO.Path]::IsPathRooted($Kubeconfig)){$Kubeconfig}else{Join-Path $root $Kubeconfig}
$private=Join-Path $root '.env.k3d'
$scratchRoot=[IO.Path]::GetFullPath((Join-Path $private 'backup-work'))+[IO.Path]::DirectorySeparatorChar
$work=Join-Path $scratchRoot ([guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($work)|Out-Null
$backups=Join-Path $private 'backups';[IO.Directory]::CreateDirectory($backups)|Out-Null
$keyPath=Join-Path $private 'backup.key'
if(!(Test-Path -LiteralPath $keyPath)){$bytes=[byte[]]::new(32);$rng=[Security.Cryptography.RandomNumberGenerator]::Create();$rng.GetBytes($bytes);$rng.Dispose();[IO.File]::WriteAllText($keyPath,[Convert]::ToBase64String($bytes))}
function Kube { $ErrorActionPreference='Continue'; & kubectl --kubeconfig $config -n notifyhub @args 2>&1 | ForEach-Object { "$_" }; if($LASTEXITCODE -ne 0){throw 'Backup kubectl command failed'} }
$remote='/tmp/notifyhub-'+[guid]::NewGuid().ToString('N')+'.dump'
try{
    $pod=Kube get pods -l app=postgres -o 'jsonpath={.items[0].metadata.name}'
    Kube exec $pod '--' pg_dump -U postgres -d notifyhub -Fc -f $remote
    # kubectl cp interprets Windows drive-letter paths as remote specs; use a workspace-relative local path.
    Push-Location $root
    try { Kube cp "notifyhub/${pod}:$remote" ('.env.k3d/backup-work/'+[IO.Path]::GetFileName($work)+'/database.dump') } finally { Pop-Location }
    foreach($file in @('private.pem','control.secret','runtime.json','tls.crt','tls.key')){Copy-Item -LiteralPath (Join-Path $private $file) -Destination (Join-Path $work $file)}
    [IO.File]::WriteAllText((Join-Path $work 'manifest.json'),(@{createdAt=[DateTime]::UtcNow.ToString('o');database='notifyhub';format=1}|ConvertTo-Json))
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=Join-Path $work 'snapshot.zip'
    $payload=Join-Path $work 'payload';[IO.Directory]::CreateDirectory($payload)|Out-Null
    foreach($file in @('database.dump','private.pem','control.secret','runtime.json','tls.crt','tls.key','manifest.json')){Move-Item -LiteralPath (Join-Path $work $file) -Destination (Join-Path $payload $file)}
    [IO.Compression.ZipFile]::CreateFromDirectory($payload,$zip)
    $output=Join-Path $backups ('notifyhub-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)+'.nhb')
    & $NodeExecutable (Join-Path $PSScriptRoot 'backup-crypto.mjs') encrypt $zip $output $keyPath
    if($LASTEXITCODE -ne 0){throw 'Backup encryption failed'}
    Write-Output "Encrypted backup: $output"
    Write-Output 'Keep backup.key in a separate protected/offsite location. It is deliberately excluded from the archive.'
}finally{
    try{Kube exec deployment/postgres '--' rm -f $remote}catch{Write-Warning 'Could not remove temporary dump in the owned PostgreSQL pod'}
    $resolved=[IO.Path]::GetFullPath($work)
    if(!$resolved.StartsWith($scratchRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^[a-f0-9]{32}$'){throw 'Unsafe backup scratch cleanup target'}
    if(Test-Path -LiteralPath $resolved){if((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing reparse-point cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}
}
