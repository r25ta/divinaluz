<#
.SYNOPSIS
Orquestra o ciclo completo em uma chamada só: sobe o app, roda a bateria de testes,
derruba o app. Uso principal para validar mudanças no back-end sem interação manual.
#>
$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot

& (Join-Path $scriptDir "dev-run.ps1")
if ($LASTEXITCODE -ne 0) {
    Write-Output "Abortando: app não subiu."
    exit 1
}

$testExitCode = 1
try {
    & (Join-Path $scriptDir "smoke-test.ps1")
    $testExitCode = $LASTEXITCODE
} finally {
    # Garante que o app e derrubado mesmo se o smoke-test lancar uma excecao nao tratada.
    & (Join-Path $scriptDir "dev-stop.ps1")
}

exit $testExitCode
