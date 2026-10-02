<#
.SYNOPSIS
Para a aplicação e o banco subidos por docker-run.ps1.

Por padrão apenas PARA os contêineres, preservando o banco — religar com docker-run.ps1 é rápido e
os dados de teste continuam lá. Use -Remover para apagar os contêineres (o volume do banco
sobrevive) e -Tudo para apagar também o volume e a rede, voltando a máquina ao estado anterior.

.OUTPUTS
Exit 0 sempre que não havia nada a fazer ou a parada funcionou.
#>
param(
    # Apaga os contêineres, mas preserva o volume com os dados do banco.
    [switch]$Remover,
    # Apaga contêineres, volume do banco (dados somem) e a rede.
    [switch]$Tudo
)

$ErrorActionPreference = "SilentlyContinue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. (Join-Path $PSScriptRoot "docker-comum.ps1")

$docker = Find-Docker
if (-not $docker) {
    Write-Output "Docker nao encontrado - nada para parar."
    exit 0
}
Add-DockerAoPath $docker
if ((Invoke-Docker $docker info --format '{{.OSType}}').Codigo -ne 0) {
    Write-Output "Daemon do Docker nao responde - os conteineres ja estao parados com ele."
    exit 0
}

$algoFeito = $false

foreach ($nome in @($script:ContainerApp, $script:ContainerDb)) {
    if (-not (Test-Container $docker $nome)) { continue }

    if ($Remover -or $Tudo) {
        Invoke-Docker $docker rm -f $nome | Out-Null
        Write-Output "Removido: $nome"
    } elseif (Test-ContainerRodando $docker $nome) {
        Invoke-Docker $docker stop $nome | Out-Null
        Write-Output "Parado: $nome"
    } else {
        Write-Output "Ja estava parado: $nome"
    }
    $algoFeito = $true
}

if ($Tudo) {
    if ((Invoke-Docker $docker volume rm $script:VolumeDb).Codigo -eq 0) {
        Write-Output "Removido o volume $script:VolumeDb (dados do banco apagados)."
    }
    if ((Invoke-Docker $docker network rm $script:Rede).Codigo -eq 0) {
        Write-Output "Removida a rede $script:Rede."
    }
    $algoFeito = $true
}

if (-not $algoFeito) {
    Write-Output "Nenhum conteiner do divinaluz encontrado. Nada para parar."
} elseif (-not ($Remover -or $Tudo)) {
    Write-Output "Banco preservado. Religue com: .\scripts\docker-run.ps1"
}
exit 0
