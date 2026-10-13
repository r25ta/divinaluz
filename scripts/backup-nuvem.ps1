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

# Tamanho do banco e da auditoria (2026-10-12): o plano gratuito do Neon tem 0,5 GB, e a auditoria
# (V44) nao apaga nada. Cada execucao acrescenta uma linha em tamanho-banco.csv (o historico do
# crescimento) e, passando do limite, avisa: aviso no log, arquivo ALERTA-ESPACO-BANCO.txt na pasta
# dos backups e uma notificacao do Windows. Falhar aqui nao invalida o backup, que ja foi feito.
$limiteBancoMb = 300       # ~60% dos 512 MB do Neon gratuito
$limiteAuditoriaMb = 100
try {
    $psql = Join-Path (Split-Path $pgDump) "psql.exe"
    $sql = "SELECT round(pg_database_size(current_database()) / 1048576.0, 1), " +
           "round((pg_total_relation_size('auditoria') + pg_total_relation_size('auditoria_campo')) / 1048576.0, 1), " +
           "(SELECT count(*) FROM auditoria)"
    # Sem "| Select-Object -First 1": ele interrompe o psql no meio e o codigo de saida vira erro.
    $saida = @(& $psql $DatabaseUrl -tA -F ";" -c $sql)
    if ($LASTEXITCODE -ne 0 -or $saida.Count -eq 0) { throw "psql nao respondeu" }
    $linha = $saida[0]
    $bancoMb, $auditoriaMb, $auditoriaLinhas = $linha.Trim().Split(";")

    $historico = Join-Path $Destino "tamanho-banco.csv"
    if (-not (Test-Path $historico)) {
        "data;banco_mb;auditoria_mb;auditoria_registros" | Out-File $historico -Encoding utf8
    }
    "$(Get-Date -Format 'yyyy-MM-dd HH:mm');$bancoMb;$auditoriaMb;$auditoriaLinhas" | Out-File $historico -Append -Encoding utf8
    Write-Host "Tamanho: banco $bancoMb MB (limite do plano: 512 MB), auditoria $auditoriaMb MB ($auditoriaLinhas registros)."

    $alerta = Join-Path $Destino "ALERTA-ESPACO-BANCO.txt"
    $cultura = [Globalization.CultureInfo]::InvariantCulture
    if ([double]::Parse($bancoMb, $cultura) -ge $limiteBancoMb -or [double]::Parse($auditoriaMb, $cultura) -ge $limiteAuditoriaMb) {
        $mensagem = "Divina Luz: o banco da nuvem esta com $bancoMb MB (limite do plano 512 MB) e a auditoria com $auditoriaMb MB. Hora de decidir quanto tempo guardar a auditoria."
        Write-Warning $mensagem
        "$(Get-Date -Format 'dd/MM/yyyy HH:mm') - $mensagem" | Out-File $alerta -Encoding utf8
        try {
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
            $modelo = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
            $textos = $modelo.GetElementsByTagName("text")
            $textos.Item(0).AppendChild($modelo.CreateTextNode("Divina Luz - espaco do banco")) | Out-Null
            $textos.Item(1).AppendChild($modelo.CreateTextNode($mensagem)) | Out-Null
            $id = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe'
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($id).Show(
                [Windows.UI.Notifications.ToastNotification]::new($modelo))
        } catch {
            # Sem sessao interativa (tarefa rodando sem ninguem logado): ficam o log e o arquivo.
        }
    } elseif (Test-Path $alerta) {
        Remove-Item $alerta -Force
    }
} catch {
    Write-Warning "Nao foi possivel medir o tamanho do banco: $($_.Exception.Message)"
}
