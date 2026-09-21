<#
.SYNOPSIS
Para a aplicação subida por dev-run.ps1, matando toda a árvore de processos
(mvnw.cmd -> cmd -> java), já que só derrubar o PID salvo deixa órfão o processo java real.
#>
$ErrorActionPreference = "SilentlyContinue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $PSScriptRoot ".dev-app.pid"

function Stop-ProcessTree([int]$ProcessId) {
    Get-CimInstance Win32_Process -Filter "ParentProcessId = $ProcessId" -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-ProcessTree -ProcessId $_.ProcessId }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path $pidFile)) {
    Write-Output "Nenhum PID registrado (.dev-app.pid nao existe). Nada para parar."
    exit 0
}

$appPid = Get-Content $pidFile -ErrorAction SilentlyContinue
if ($appPid -and (Get-Process -Id $appPid -ErrorAction SilentlyContinue)) {
    Stop-ProcessTree -ProcessId $appPid
    Write-Output "App parado (PID $appPid e processos filhos)."
} else {
    Write-Output "Processo $appPid nao encontrado (ja estava parado)."
}

Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
