# Backup do banco da nuvem para uma pasta local (ver DEPLOY-NUVEM.md).
#
# Existe porque o plano gratuito do banco não tem backup que sirva: o Neon free guarda só 6 horas de
# histórico. Então a cópia que importa é esta, num lugar que é seu.
#
# A connection string NÃO fica neste arquivo nem na tarefa agendada. Guarde-a uma vez na sua conta do
# Windows (abra o PowerShell e rode, com a sua string no lugar):
#
#   setx DIVINALUZ_DATABASE_URL "postgresql://usuario:senha@host/banco?sslmode=require"
#
# Depois feche e reabra o PowerShell. Uso:
#
#   .\scripts\backup-nuvem.ps1                      # usa a variável e a pasta padrão
#   .\scripts\backup-nuvem.ps1 -Destino "D:\copias" # outra pasta
#   .\scripts\backup-nuvem.ps1 -ManterDias 90       # outra retenção
#
# ATENÇÃO (LGPD): o arquivo gerado contém os dados reais dos assistidos. Guarde-o só onde você
# controla o acesso e não o envie por e-mail nem por aplicativo de mensagem.

param(
    [string]$DatabaseUrl = $env:DIVINALUZ_DATABASE_URL,
    [string]$Destino = (Join-Path $env:USERPROFILE "OneDrive\Backups\divinaluz"),
    [int]$ManterDias = 60
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($DatabaseUrl)) {
    Write-Error "Sem connection string. Defina DIVINALUZ_DATABASE_URL (veja o comentário no topo deste arquivo) ou passe -DatabaseUrl."
    exit 1
}

$pgDump = Get-ChildItem "C:\Program Files\PostgreSQL\*\bin\pg_dump.exe" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $pgDump) {
    Write-Error "pg_dump.exe nao encontrado em C:\Program Files\PostgreSQL\*\bin. Instale as ferramentas de linha de comando do PostgreSQL."
    exit 1
}

if (-not (Test-Path $Destino)) {
    New-Item -ItemType Directory -Path $Destino -Force | Out-Null
}

$agora = Get-Date -Format "yyyy-MM-dd_HHmm"
$arquivo = Join-Path $Destino "divinaluz_$agora.dump"

Write-Host "Copiando o banco da nuvem para $arquivo ..."
# Formato personalizado (-Fc): comprimido e restauravel tabela a tabela com pg_restore.
# --no-owner/--no-privileges: o usuario do banco na nuvem nao existe na maquina onde se restaura.
& $pgDump --format=custom --no-owner --no-privileges --file=$arquivo $DatabaseUrl
if ($LASTEXITCODE -ne 0) {
    Write-Error "pg_dump falhou (codigo $LASTEXITCODE). Nenhum backup foi gravado nesta execucao."
    Remove-Item $arquivo -ErrorAction SilentlyContinue
    exit 1
}

# Um dump vazio ou minusculo e sinal de que algo deu errado mesmo com codigo 0.
$tamanhoKb = [math]::Round((Get-Item $arquivo).Length / 1KB, 1)
if ($tamanhoKb -lt 5) {
    Write-Error "O arquivo gerado tem apenas $tamanhoKb KB — isso nao parece um backup do banco. Confira a connection string."
    exit 1
}
Write-Host "OK: $tamanhoKb KB."

# Conferencia de verdade: o pg_restore -l le o indice do dump. Se o arquivo estiver corrompido,
# falha aqui, e nao no dia em que precisarmos restaurar.
$pgRestore = Join-Path (Split-Path $pgDump) "pg_restore.exe"
if (Test-Path $pgRestore) {
    $itens = (& $pgRestore --list $arquivo | Measure-Object -Line).Lines
    if ($LASTEXITCODE -ne 0) {
        Write-Error "O arquivo gerado nao pode ser lido pelo pg_restore: backup invalido."
        exit 1
    }
    Write-Host "Conferido: o dump e legivel ($itens linhas de indice)."
}

$antigos = Get-ChildItem $Destino -Filter "divinaluz_*.dump" |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$ManterDias) }
if ($antigos) {
    $antigos | Remove-Item -Force
    Write-Host "Apagados $($antigos.Count) backup(s) com mais de $ManterDias dias."
}

$total = (Get-ChildItem $Destino -Filter "divinaluz_*.dump" | Measure-Object).Count
Write-Host "Pronto. $total backup(s) em $Destino."
