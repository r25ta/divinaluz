<#
.SYNOPSIS
Sobe a aplicação em contêiner com um PostgreSQL próprio, no perfil "prod" — o mesmo arranjo do
deploy na nuvem. Serve para ensaiar o deploy, e não para o dia a dia de desenvolvimento (para esse,
use dev-run.ps1, que tem reload e fala com o banco local).

Diferenças que importam em relação ao dev-run.ps1:
  - perfil "prod": sem contexto /divinaluz (abre na raiz), sem show-sql, lazy-initialization ligado;
  - banco próprio num contêiner, em volume separado — não toca no banco de desenvolvimento;
  - as migrations rodam num banco que começa vazio, exercitando o FlywayBancoVazioConfig.

.OUTPUTS
Exit 0 e "APP READY" com o endereço. Exit 1 e "APP FAILED"/"TIMEOUT" com o final do log.
#>
param(
    [int]$Port = 8080,
    [int]$TimeoutSeconds = 180,
    [string]$Tag = "divinaluz:local",
    # Apaga o volume do banco antes de subir, para ensaiar o deploy num banco realmente vazio.
    [switch]$Zerar,
    # Reconstrói a imagem antes de subir (chama docker-build.ps1).
    [switch]$Rebuild,
    # Senha do admin no primeiro boot (ADMIN_SENHA_REDEFINIR). Em branco, vale o hash das migrations,
    # que está no repositório — aceitável aqui porque isto é local, mas nunca na nuvem.
    [string]$SenhaAdmin = "",
    # Limites parecidos com o plano gratuito do Render, para a medição de subida ter algum sentido.
    [string]$Memoria = "512m",
    [string]$Cpus = "1"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
. (Join-Path $PSScriptRoot "docker-comum.ps1")

$docker = Get-DockerOuSair
$senhaDb = "divinaluz_local"   # só existe dentro desta máquina, em contêiner de teste

if ($Rebuild) {
    & (Join-Path $PSScriptRoot "docker-build.ps1") -Tag $Tag
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

$existeImagem = (Invoke-Docker $docker images -q $Tag).Saida.Trim()
if (-not $existeImagem) {
    Write-Output "Imagem $Tag nao existe ainda - construindo primeiro."
    & (Join-Path $PSScriptRoot "docker-build.ps1") -Tag $Tag
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

if (Test-ContainerRodando $docker $script:ContainerApp) {
    Write-Output "APP READY (ja estava rodando) - http://localhost:$Port/"
    exit 0
}

Invoke-Docker $docker network create $script:Rede | Out-Null   # falha aqui significa "ja existe"

if ($Zerar) {
    Write-Output "Zerando o banco (removendo contêiner e volume)..."
    Invoke-Docker $docker rm -f $script:ContainerDb | Out-Null
    Invoke-Docker $docker volume rm $script:VolumeDb | Out-Null
}

# --- Banco ---
# Guardado para a mensagem final poder dizer a verdade sobre a senha do admin: num banco novo vale o
# hash das migrations, num banco reaproveitado vale o que já estava lá (que pode ter sido trocado
# numa subida anterior, ou pela própria tela).
$bancoNovo = $false
if (-not (Test-ContainerRodando $docker $script:ContainerDb)) {
    if (Test-Container $docker $script:ContainerDb) {
        Write-Output "Religando o banco..."
        Invoke-Docker $docker start $script:ContainerDb | Out-Null
    } else {
        $bancoNovo = $true
        Write-Output "Criando o banco ($script:ImagemPostgres)..."
        $criado = Invoke-Docker $docker run -d --name $script:ContainerDb --network $script:Rede `
            -e "POSTGRES_PASSWORD=$senhaDb" -e "POSTGRES_USER=postgres" -e "POSTGRES_DB=divinaluz_db" `
            -v "$($script:VolumeDb):/var/lib/postgresql/data" `
            -p 55432:5432 $script:ImagemPostgres
        if ($criado.Codigo -ne 0) {
            Write-Output "APP FAILED - nao consegui criar o conteiner do banco:"
            Write-Output $criado.Saida.Trim()
            exit 1
        }
    }
}

# Esperar o banco ACEITAR CONEXAO, e nao apenas existir. Sem isto a aplicacao sobe primeiro, leva
# "Connection refused", e morre: ela nao tem retry de banco na inicializacao. O sintoma no log e
# "Nao foi possivel preparar o banco vazio para o Flyway", que parece erro de migration e nao de
# ordem de subida - foi exatamente esse o tropeço no ensaio de 2026-10-02.
Write-Output "Esperando o banco aceitar conexoes..."
$deadlineDb = (Get-Date).AddSeconds(60)
$bancoPronto = $false
while ((Get-Date) -lt $deadlineDb) {
    $pronto = Invoke-Docker $docker exec $script:ContainerDb pg_isready -U postgres -d divinaluz_db
    if ($pronto.Codigo -eq 0) { $bancoPronto = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $bancoPronto) {
    Write-Output "APP FAILED - o banco nao ficou pronto em 60s. Log do banco:"
    Write-Output (Invoke-Docker $docker logs $script:ContainerDb --tail 20).Saida
    exit 1
}

# --- Aplicacao ---
Invoke-Docker $docker rm -f $script:ContainerApp | Out-Null

$env_db = "DATABASE_URL=postgresql://postgres:$senhaDb@$($script:ContainerDb):5432/divinaluz_db"
$argumentos = @(
    "run", "-d", "--name", $script:ContainerApp, "--network", $script:Rede,
    "-p", "$($Port):8080",
    "--memory=$Memoria", "--cpus=$Cpus",
    "-e", "SPRING_PROFILES_ACTIVE=prod",
    "-e", "PORT=8080",
    "-e", $env_db,
    "-e", "APP_BASE_URL=http://localhost:$Port"
)
if ($SenhaAdmin) { $argumentos += @("-e", "ADMIN_SENHA_REDEFINIR=$SenhaAdmin") }
$argumentos += $Tag

$subiu = Invoke-Docker $docker @argumentos
if ($subiu.Codigo -ne 0) {
    Write-Output "APP FAILED - nao consegui criar o conteiner da aplicacao (porta $Port ocupada?):"
    Write-Output $subiu.Saida.Trim()
    exit 1
}

Write-Output "Subindo aplicacao (perfil prod, $Memoria / $Cpus cpu)..."

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $log = (Invoke-Docker $docker logs $script:ContainerApp).Saida

    if ($log -match "Started DivinaluzApplication") {
        $migrations = [regex]::Match($log, "Successfully applied (\d+) migrations.*?version (v[\d.]+)")
        if ($migrations.Success) {
            Write-Output "  migrations: $($migrations.Groups[1].Value) aplicadas, agora em $($migrations.Groups[2].Value)"
        }
        if ($log -match "Senha do admin redefinida") {
            Write-Output "  senha do admin: a que voce passou em -SenhaAdmin"
        } elseif ($bancoNovo) {
            Write-Output "  senha do admin: a das migrations (esta no repositorio - ver smoke-test.ps1)"
        } else {
            Write-Output "  senha do admin: a que ja estava no banco (esta subida nao mexeu nela)"
        }
        Write-Output "APP READY - http://localhost:$Port/  (login: admin)"
        Write-Output "  Parar com: .\scripts\docker-stop.ps1"
        exit 0
    }

    # Silencio nao e sucesso: sem estas duas saidas, um contêiner que morreu na subida ficaria
    # esperando o timeout inteiro antes de dizer qualquer coisa.
    if ($log -match "Application run failed" -or $log -match "APPLICATION FAILED TO START") {
        Write-Output "APP FAILED - ultimas linhas do log:"
        Write-Output (Invoke-Docker $docker logs $script:ContainerApp --tail 40).Saida
        exit 1
    }
    if (-not (Test-ContainerRodando $docker $script:ContainerApp)) {
        $saida = (Invoke-Docker $docker inspect -f '{{.State.ExitCode}}' $script:ContainerApp).Saida.Trim()
        Write-Output "APP FAILED - o conteiner parou (exit $saida). Ultimas linhas do log:"
        Write-Output (Invoke-Docker $docker logs $script:ContainerApp --tail 40).Saida
        exit 1
    }
}

Write-Output "TIMEOUT esperando o app subir (${TimeoutSeconds}s) - ultimas linhas do log:"
Write-Output (Invoke-Docker $docker logs $script:ContainerApp --tail 40).Saida
exit 1
