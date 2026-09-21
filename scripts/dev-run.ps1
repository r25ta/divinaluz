<#
.SYNOPSIS
Compila e sobe a aplicação em background, esperando até ficar pronta (ou falhar).
Substitui o ciclo manual de: compile -> start -> wait loop -> grep no log.

.OUTPUTS
Exit 0 e "APP READY" se subiu com sucesso (ou já estava rodando).
Exit 1 e "APP FAILED"/"TIMEOUT" caso contrário, com o final do log.
#>
param(
    [int]$TimeoutSeconds = 90,
    [int]$Port = 8081
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$logFile = Join-Path $PSScriptRoot ".dev-app.log"
$pidFile = Join-Path $PSScriptRoot ".dev-app.pid"

if (Test-Path $pidFile) {
    $existingPid = Get-Content $pidFile -ErrorAction SilentlyContinue
    if ($existingPid -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
        Write-Output "APP READY (ja estava rodando, PID $existingPid) - http://localhost:$Port/divinaluz/"
        exit 0
    }
}

# A porta pode estar ocupada por uma instancia solta (ex: iniciada manualmente pela IDE),
# nao relacionada a este script. Libera antes de tentar subir, para nao gastar o timeout todo
# so para descobrir isso.
$stalePids = (Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue).OwningProcess | Select-Object -Unique
foreach ($stalePid in $stalePids) {
    Write-Output "Porta $Port ocupada pelo processo $stalePid - encerrando."
    Stop-Process -Id $stalePid -Force -ErrorAction SilentlyContinue
}
if ($stalePids) { Start-Sleep -Seconds 1 }

Write-Output "Compilando..."
& .\mvnw.cmd compile -q
if ($LASTEXITCODE -ne 0) {
    Write-Output "APP FAILED - erro de compilacao (mvnw compile)."
    exit 1
}

if (Test-Path $logFile) { Remove-Item $logFile -Force }

$proc = Start-Process -FilePath ".\mvnw.cmd" -ArgumentList "spring-boot:run" `
    -RedirectStandardOutput $logFile -NoNewWindow -PassThru
$proc.Id | Out-File -FilePath $pidFile -Encoding ascii

Write-Output "Subindo aplicacao (PID $($proc.Id))..."

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 1
    if (-not (Test-Path $logFile)) { continue }
    $content = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
    if ($content -match "Started DivinaluzApplication") {
        Write-Output "APP READY (PID $($proc.Id)) - http://localhost:$Port/divinaluz/"
        exit 0
    }
    if ($content -match "Application run failed" -or $content -match "BUILD FAILURE" `
            -or $content -match "APPLICATION FAILED TO START" -or $content -match "Port .* already in use" `
            -or $content -match "PortInUseException") {
        Write-Output "APP FAILED - ultimas linhas do log:"
        Get-Content $logFile -Tail 40
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
        exit 1
    }
}

Write-Output "TIMEOUT esperando o app subir (${TimeoutSeconds}s) - ultimas linhas do log:"
Get-Content $logFile -Tail 40
exit 1
