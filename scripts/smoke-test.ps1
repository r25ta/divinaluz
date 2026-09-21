<#
.SYNOPSIS
Bateria de testes end-to-end via curl contra o app já rodando (use dev-run.ps1 antes).
Cria e limpa seus próprios dados de teste (prefixo "SMOKE_TEST_"), não requer app parado.
Cobre: cadastro+tratamento, regra de 7 dias, regra das 4 sessões -> avaliação pendente,
avaliação atualizando o tratamento atual, e reset de tratamento por 3 semanas de ausência.

.OUTPUTS
Imprime PASS/FAIL por verificação e um resumo final. Exit 0 se tudo passou, 1 caso contrário.
#>
param(
    [string]$BaseUrl = "http://localhost:8081/divinaluz",
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\18\bin\psql.exe",
    [string]$DbName = "divinaluz_db",
    [string]$DbUser = "postgres",
    [string]$DbPassword = "admin"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$script:failures = @()
$nomeTeste = "SMOKE_TEST_$(Get-Date -Format yyyyMMddHHmmssfff)"

function Check([string]$Nome, [bool]$Condicao, [string]$Detalhe = "") {
    if ($Condicao) {
        Write-Output "PASS: $Nome"
    } else {
        Write-Output "FAIL: $Nome $Detalhe"
        $script:failures += $Nome
    }
}

function Invoke-CurlForm {
    param(
        [string]$Method = "GET",
        [string]$Url,
        [hashtable]$Form = $null
    )
    $curlArgs = @("-s", "-i")
    if ($Method -eq "POST") {
        $curlArgs += @("-X", "POST")
        if ($Form) {
            foreach ($key in $Form.Keys) {
                $curlArgs += @("-d", "$key=$($Form[$key])")
            }
        }
    }
    $curlArgs += $Url
    $raw = & curl.exe @curlArgs
    $text = $raw -join "`n"
    $statusLine = ($text -split "`r?`n")[0]
    $statusCode = 0
    if ($statusLine -match 'HTTP/[\d.]+\s+(\d+)') { $statusCode = [int]$Matches[1] }
    $location = $null
    if ($text -match '(?m)^Location:\s*(.+)$') { $location = $Matches[1].Trim() }
    return [pscustomobject]@{ StatusCode = $statusCode; Location = $location; Body = $text }
}

function Invoke-Sql([string]$Sql) {
    $env:PGPASSWORD = $DbPassword
    & $PsqlPath -U $DbUser -d $DbName -h localhost -t -c $Sql 2>&1
}

try {
    # 1. Index responde
    $r = Invoke-CurlForm -Url "$BaseUrl/"
    Check "Index responde 200" ($r.StatusCode -eq 200)

    # 2. Cadastro com tratamento inicial P2 (id=2 no seed)
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeTeste; vinculo = "ASSISTIDO"; tratamentoAtual = "2" }
    Check "Cadastro redireciona para /" ($r.StatusCode -eq 302 -and $r.Location -match '/divinaluz/?$')

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    $assistidoId = $null
    if ($r.Body -match "<td>(\d+)</td>\s*<td>$([regex]::Escape($nomeTeste))</td>") { $assistidoId = $Matches[1] }
    Check "Assistido de teste aparece na listagem com ID" ($null -ne $assistidoId)
    if (-not $assistidoId) { throw "Não foi possível continuar sem o ID do assistido de teste." }

    $prontuarioUrl = "$BaseUrl/prontuario/$assistidoId"

    # 3. Prontuário mostra tratamento inicial
    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra tratamento P2" ($r.Body -match "P2 - ")

    # 4. Primeira sessão do ciclo
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-01"; visto = "true" }
    Check "1ª sessão registrada (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    # 5. Regra dos 7 dias: sessão 2 dias depois deve ser bloqueada
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-03" }
    Check "Sessão em <7 dias é bloqueada (volta para nova-sessao)" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    # 6-8. Sessões 2, 3 e 4 do ciclo (9, 16, 23 dias depois da anterior — dentro da tolerância)
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-10" }
    Check "2ª sessão registrada" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-17" }
    Check "3ª sessão registrada" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-24" }
    Check "4ª sessão registrada" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    # 9. Regra das 4 sessões: 5ª sessão deve ser bloqueada até nova Avaliação
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-31" }
    Check "5ª sessão bloqueada por avaliação pendente" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    # 10. Registrar avaliação indicando novo tratamento (P3E, id=5) deve destravar e atualizar o tratamento atual
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/avaliacao" -Form @{ data = "2024-01-31"; entrevistador = "Smoke Test"; evolucao = "MELHOR"; tratamentoIndicado = "5" }
    Check "Avaliação registrada (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento atual atualizado para P3E após avaliação" ($r.Body -match "P3E - ")

    # 11. Agora a 5ª sessão deve ser aceita
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-31" }
    Check "5ª sessão aceita após avaliação" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    # 12. Reset por 3 semanas de ausência: próxima sessão 26 dias depois reinicia em P2
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-02-26" }
    Check "Sessão após 3 semanas de hiato é aceita (reinício de ciclo)" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento reiniciado em P2 após hiato de 3+ semanas" ($r.Body -match "P2 - ")
    Check "Nova sessão do ciclo reiniciado é a 1ª SÉRIE" ($r.Body -match "1ª SÉRIE")

} finally {
    Write-Output "Limpando dados de teste ($nomeTeste)..."
    Invoke-Sql "DELETE FROM sessao_tratamento WHERE assistido_id IN (SELECT id FROM assistido WHERE nome = '$nomeTeste');" | Out-Null
    Invoke-Sql "DELETE FROM avaliacao WHERE assistido_id IN (SELECT id FROM assistido WHERE nome = '$nomeTeste');" | Out-Null
    Invoke-Sql "DELETE FROM assistido WHERE nome = '$nomeTeste';" | Out-Null
}

Write-Output ""
if ($script:failures.Count -eq 0) {
    Write-Output "TODOS OS TESTES PASSARAM."
    exit 0
} else {
    Write-Output "$($script:failures.Count) TESTE(S) FALHARAM: $($script:failures -join ', ')"
    exit 1
}
