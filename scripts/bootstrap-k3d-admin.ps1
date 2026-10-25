param([string]$Kubeconfig = '.env.k3d/kubeconfig')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$config = if ([IO.Path]::IsPathRooted($Kubeconfig)) { $Kubeconfig } else { Join-Path $root $Kubeconfig }
$seed = [IO.File]::ReadAllText((Join-Path $root '.env.k3d/admin-seed.sql'))
$seed | & kubectl --kubeconfig $config -n notifyhub exec -i deployment/postgres -- sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -U "$POSTGRES_USER" -d notifyhub -v ON_ERROR_STOP=1'
if ($LASTEXITCODE -ne 0) { throw 'Initial tenant seed failed; no public registration endpoint was enabled' }
Write-Output 'Local ADMIN provisioned; use ignored .env.k3d/admin-login.json for the password.'
