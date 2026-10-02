<#
.SYNOPSIS
Atualiza a imagem Docker da aplicação (o mesmo Dockerfile que o Render usa no deploy).

Build em dois estágios: compila com Maven e grava o arquivo de CDS, que é o que derruba a subida
de minutos para ~30s num contêiner apertado. Por isso leva de 2 a 10 minutos — na primeira vez,
baixando as dependências, mais perto de 10.

.OUTPUTS
Exit 0 e "IMAGEM PRONTA" com o tamanho. Exit 1 e "BUILD FALHOU" com o final do log.
#>
param(
    [string]$Tag = "divinaluz:local",
    # Ignora o cache de camadas. Útil quando se desconfia que o build reaproveitou algo velho.
    [switch]$SemCache
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot "docker-comum.ps1")

$docker = Get-DockerOuSair
$logFile = Join-Path $PSScriptRoot ".docker-build.log"

$argumentos = @("build", "--progress=plain", "-t", $Tag)
if ($SemCache) { $argumentos += "--no-cache" }
$argumentos += "."

Write-Output "Construindo $Tag (pode levar alguns minutos)..."
# A saida vai para arquivo em vez da tela porque o build do Maven + o treino do CDS geram centenas
# de linhas, e o que interessa no sucesso e so o resultado. Em caso de erro, o final do log aparece.
# O docker escreve o progresso em stderr, por isso a chamada passa pelo Invoke-Docker (ver comum).
$resultado = Invoke-Docker $docker @argumentos
Set-Content -Path $logFile -Value $resultado.Saida -Encoding utf8

if ($resultado.Codigo -ne 0) {
    Write-Output "BUILD FALHOU (exit $($resultado.Codigo)) - ultimas linhas do log:"
    Get-Content $logFile -Tail 40
    Write-Output ""
    Write-Output "Log completo: $logFile"
    exit 1
}

$tamanho = (Invoke-Docker $docker images $Tag --format '{{.Size}}').Saida.Trim()
Write-Output "IMAGEM PRONTA - $Tag ($tamanho)"
Write-Output "  Suba com: .\scripts\docker-run.ps1"
exit 0
