<#
.SYNOPSIS
Hook PostToolUse (Edit|Write): compila automaticamente quando o arquivo editado for .java,
para nao precisar chamar "mvnw compile" manualmente a cada mudanca. So imprime saida em caso
de erro (silencioso quando compila com sucesso).
#>
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$json = [Console]::In.ReadToEnd() | ConvertFrom-Json
$filePath = $json.tool_input.file_path

if (-not $filePath -or -not $filePath.EndsWith(".java")) {
    exit 0
}

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$output = & .\mvnw.cmd compile -q 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Output "ERRO DE COMPILACAO apos editar $filePath :"
    $output | Select-Object -Last 40
    exit 1
}

exit 0
