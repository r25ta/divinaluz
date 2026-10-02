<#
.SYNOPSIS
O que docker-build.ps1, docker-run.ps1 e docker-stop.ps1 compartilham: achar o docker.exe,
falar com o daemon e os nomes dos objetos (imagem, contêineres, rede, volume).

Não é para ser executado direto — os outros scripts fazem dot-source dele.
#>

# Nomes fixos dos objetos que os scripts criam. Mantidos aqui para os três concordarem; mudar um
# nome aqui muda em todos.
$script:Imagem        = "divinaluz:local"
$script:ContainerApp  = "divinaluz-app"
$script:ContainerDb   = "divinaluz-db"
$script:Rede          = "divinaluz-net"
$script:VolumeDb      = "divinaluz-dbdata"
$script:ImagemPostgres = "postgres:16-alpine"

<#
Acha o docker.exe. Existe porque o instalador por usuário do Docker Desktop (que é o caso desta
máquina) NÃO entra no PATH da máquina: o docker fica em
%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin, e não no Program Files padrão. Sem isto, todo
comando falha com CommandNotFoundException mesmo com o Docker Desktop aberto e funcionando — e o
diagnóstico natural ("Docker não está instalado") é o errado. Ver seção 6 do CLAUDE.md.
#>
function Find-Docker {
    $noPath = Get-Command docker -ErrorAction SilentlyContinue
    if ($noPath) { return $noPath.Source }

    $candidatos = @(
        "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin\docker.exe",
        "$env:ProgramFiles\Docker\Docker\resources\bin\docker.exe",
        "${env:ProgramFiles(x86)}\Docker\Docker\resources\bin\docker.exe",
        "$env:ProgramData\DockerDesktop\version-bin\docker.exe"
    )
    foreach ($c in $candidatos) {
        if ($c -and (Test-Path $c)) { return $c }
    }

    # Último recurso: deduzir da instalação a partir do processo em execução, que funciona mesmo se
    # o Docker tiver sido instalado num caminho que não está na lista acima.
    $proc = Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($proc -and $proc.Path) {
        $raiz = Split-Path $proc.Path -Parent
        $viaProcesso = Join-Path $raiz "resources\bin\docker.exe"
        if (Test-Path $viaProcesso) { return $viaProcesso }
    }
    return $null
}

<#
Põe a pasta do docker.exe no PATH **deste processo**.

Não é conveniência: chamar o docker.exe por caminho completo faz o `build` falhar com
  error getting credentials - err: exec: "docker-credential-desktop": executable file not found in %PATH%
porque o docker procura o auxiliar de credenciais (e outros programas vizinhos) no PATH, não ao lado
de si mesmo. Como nesta máquina a pasta não está no PATH da máquina (ver Find-Docker), todo script
que vá construir imagem precisa disto antes.
#>
function Add-DockerAoPath([string]$Docker) {
    $pasta = Split-Path $Docker -Parent
    $atual = $env:Path -split ';'
    if ($atual -notcontains $pasta) { $env:Path = "$pasta;$env:Path" }
}

<#
Devolve o caminho do docker.exe ou encerra o script com instrução. Também confere que o daemon
responde: ter o executável não garante que o Docker Desktop esteja de pé, e o erro de daemon
desligado é bem diferente do de executável ausente.
#>
function Get-DockerOuSair {
    $docker = Find-Docker
    if (-not $docker) {
        Write-Output "DOCKER AUSENTE - nao encontrei o docker.exe."
        Write-Output "  Instale o Docker Desktop, ou passe o caminho em -DockerExe se ele estiver em outro lugar."
        exit 1
    }
    Add-DockerAoPath $docker
    $info = Invoke-Docker $docker info --format '{{.OSType}}'
    if ($info.Codigo -ne 0) {
        Write-Output "DOCKER PARADO - encontrei o docker.exe, mas o daemon nao responde."
        Write-Output "  Abra o Docker Desktop e espere ele ficar 'Engine running', depois rode de novo."
        Write-Output "  Executavel: $docker"
        exit 1
    }
    return $docker
}

<#
Executa o docker e devolve { Codigo, Saida }, sem nunca lançar.

Existe por um motivo específico do Windows PowerShell 5.1: ele transforma **cada linha de stderr**
de um executável nativo num ErrorRecord (NativeCommandError), e com $ErrorActionPreference = "Stop"
isso aborta o script — inclusive quando o comando terminou com 0, e inclusive quando a falha era
esperada (um `volume rm` de volume que não existe, por exemplo). Por isso toda chamada ao docker
nestes scripts passa por aqui, e quem chama decide o que fazer com $resultado.Codigo.

Sem bloco param() de propósito: com parâmetros declarados, o binder do PowerShell tenta ler
"ps", "-a" e "--filter" como nomes de parâmetro desta função e falha com "a positional parameter
cannot be found". Usando $args, os argumentos chegam crus e a chamada fica igual à linha de comando:
Invoke-Docker $docker ps -a --filter ...
#>
function Invoke-Docker {
    if ($args.Count -lt 2) { throw "Invoke-Docker: informe o docker.exe e ao menos um argumento." }
    $exe = $args[0]
    $resto = @($args[1..($args.Count - 1)])

    $anterior = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $saida = & $exe @resto 2>&1 | Out-String
        return [pscustomobject]@{ Codigo = $LASTEXITCODE; Saida = $saida }
    } finally {
        $ErrorActionPreference = $anterior
    }
}

<# Verdadeiro se existe um contêiner com esse nome (rodando ou parado). #>
function Test-Container([string]$Docker, [string]$Nome) {
    $r = Invoke-Docker $Docker ps -a --filter "name=^/$Nome$" --format '{{.Names}}'
    return ($r.Saida.Trim() -eq $Nome)
}

<# Verdadeiro se o contêiner existe E está rodando. #>
function Test-ContainerRodando([string]$Docker, [string]$Nome) {
    if (-not (Test-Container $Docker $Nome)) { return $false }
    $r = Invoke-Docker $Docker inspect -f '{{.State.Running}}' $Nome
    return ($r.Saida.Trim() -eq 'true')
}
