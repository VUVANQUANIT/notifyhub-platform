param([switch]$Prebuilt)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
function Invoke-Docker { $ErrorActionPreference='Continue'; & docker @args 2>&1 | ForEach-Object { "$_" }; if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed' } }
Push-Location $root
try {
    foreach ($service in @('api-gateway','auth-service','campaign-service','notification-service','reporting-service')) {
        if ($Prebuilt) {
            $jar = Join-Path $root "backend/$service/target/$service-0.1.0-SNAPSHOT.jar"
            if (!(Test-Path -LiteralPath $jar)) { throw "Run Maven verify first; missing $service Boot JAR" }
            $context = Join-Path $root ".env.k3d/images/$service"
            [IO.Directory]::CreateDirectory($context) | Out-Null
            Copy-Item -LiteralPath $jar -Destination (Join-Path $context 'app.jar')
            Invoke-Docker build -t "notifyhub/${service}:local" -f backend/Dockerfile.prebuilt $context
        } else { Invoke-Docker build -t "notifyhub/${service}:local" -f backend/Dockerfile --build-arg "MODULE=$service" . }
        if ($LASTEXITCODE -ne 0) { throw "Image build failed: $service" }
    }
    Invoke-Docker build -t notifyhub/frontend:local -f frontend/Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'Frontend image build failed' }
} finally { Pop-Location }
