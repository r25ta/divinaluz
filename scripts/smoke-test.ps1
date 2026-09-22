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

    # 2. Cadastro com tratamento inicial P2 (id=2 no seed). Desde o item 4, escolher um
    # tratamento já no cadastro exige a data da 1ª sessão, que já registra a 1ª SÉRIE.
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeTeste; vinculo = "ASSISTIDO"; tratamentoAtual = "2"; dataPrimeiraSessao = "2024-01-01" }
    Check "Cadastro redireciona para /" ($r.StatusCode -eq 302 -and $r.Location -match '/divinaluz/?$')

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    $assistidoId = $null
    if ($r.Body -match "$([regex]::Escape($nomeTeste))[\s\S]*?prontuario/(\d+)") { $assistidoId = $Matches[1] }
    Check "Assistido de teste aparece na listagem com ID" ($null -ne $assistidoId)
    if (-not $assistidoId) { throw "Não foi possível continuar sem o ID do assistido de teste." }

    $prontuarioUrl = "$BaseUrl/prontuario/$assistidoId"

    # 3. Prontuário mostra tratamento inicial e a 1ª sessão já registrada automaticamente
    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra tratamento P2" ($r.Body -match ">P2<")
    Check "1ª sessão já registrada automaticamente ao definir o tratamento" ($r.Body -match "1ª SÉRIE")

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

    # 9. Regra das 4 sessões: 5ª sessão deve ser bloqueada até Avaliação + Entrevista
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-31" }
    Check "5ª sessão bloqueada por avaliação pendente" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    # 10a. Registrar a Avaliação (diagnóstico) — ainda não define tratamento nem destrava a 5ª sessão
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/avaliacao" -Form @{ data = "2024-01-31"; evolucao = "MELHOR"; historico = "Historico de teste"; observacoes = "Observacoes de teste" }
    Check "Avaliação registrada (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra observações da avaliação" ($r.Body -match "Observacoes de teste")
    $avaliacaoId = $null
    if ($r.Body -match "nova-entrevista\?avaliacaoId=(\d+)") { $avaliacaoId = $Matches[1] }
    Check "Prontuário oferece 'Registrar Entrevista' para a avaliação" ($null -ne $avaliacaoId)

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-31" }
    Check "5ª sessão continua bloqueada só com a Avaliação (falta a Entrevista)" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    # 10b. Registrar a Entrevista vinculada a essa avaliação, indicando novo tratamento (P3E, id=5):
    # deve destravar a 5ª sessão e atualizar o tratamento atual
    if ($avaliacaoId) {
        $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/entrevista?avaliacaoId=$avaliacaoId" -Form @{ data = "2024-01-31"; entrevistador = "Smoke Test"; tratamentoIndicado = "5" }
        Check "Entrevista registrada (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")
    }

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento atual atualizado para P3E após entrevista" ($r.Body -match ">P3E<")

    # 11. Agora a 5ª sessão deve ser aceita
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-31" }
    Check "5ª sessão aceita após avaliação + entrevista" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    # 12. Reset por 3 semanas de ausência: próxima sessão 26 dias depois reinicia em P2
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-02-26" }
    Check "Sessão após 3 semanas de hiato é aceita (reinício de ciclo)" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento reiniciado em P2 após hiato de 3+ semanas" ($r.Body -match ">P2<")
    Check "Nova sessão do ciclo reiniciado é a 1ª SÉRIE" ($r.Body -match "1ª SÉRIE")

    # 13a. Editar dados do assistido: nome e residência devem ser atualizados sem afetar o resto
    $r = Invoke-CurlForm -Url "$prontuarioUrl/editar"
    Check "Formulário de edição responde 200" ($r.StatusCode -eq 200)

    # tratamentoAtual precisa ir junto, assim como um <select> real do form enviaria o valor
    # já selecionado — senão a edição apagaria o tratamento atual (campo ausente vira null).
    $nomeEditado = "${nomeTeste}_EDITADO"
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/editar" -Form @{ nome = $nomeEditado; vinculo = "ASSISTIDO"; residencia = "Bairro Editado"; tratamentoAtual = "2" }
    Check "Edição salva (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra nome editado" ($r.Body -match [regex]::Escape($nomeEditado))
    Check "Prontuário mostra residência editada" ($r.Body -match "Bairro Editado")
    Check "Tratamento não foi perdido na edição (continua P2)" ($r.Body -match ">P2<")

    # 13b. Dia de assistência: definir, depois trocar com motivo, e verificar log de rastreabilidade
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/dia-frequencia" -Form @{ diaFrequencia = "TERCA_19H" }
    Check "Dia de assistência definido (redireciona)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/dia-frequencia" -Form @{ diaFrequencia = "DOMINGO_08H"; motivo = "Mudou de turno no trabalho" }
    Check "Dia de assistência alterado (redireciona)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra dia atual (Domingo, 08h)" ($r.Body -match "Domingo, 08h")
    Check "Histórico de dia mostra motivo da troca" ($r.Body -match "Mudou de turno no trabalho")
    Check "Histórico de dia mostra valor anterior (Terça-feira, 19h)" ($r.Body -match "Terça-feira, 19h")

    # 13c. Previsão da próxima entrevista = data da última avaliação + 4 semanas (2024-01-31 -> 2024-02-28)
    Check "Prontuário mostra previsão da próxima entrevista" ($r.Body -match "28/02/2024")

    # 13d. Alterar Tratamento (card do prontuário) também exige data da 1ª sessão quando muda (item 4).
    # Esse caminho usa flash attribute (erro), o que cria sessão HTTP e faz o Tomcat anexar
    # ";jsessionid=" na Location (o curl não guarda cookies entre chamadas) — por isso sem "$" no fim,
    # igual já era feito no check de "nova-sessao" mais acima.
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/tratamento" -Form @{ tratamentoAtualId = "5" }
    Check "Alterar tratamento sem data redireciona (com erro)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId")

    # A mensagem de erro é uma flash attribute (fica na sessão HTTP); como o curl não guarda
    # cookies entre chamadas, não dá para conferir o texto na página seguinte por aqui — mas o
    # efeito que importa (tratamento não mudou) é verificável e é o que garante a regra.
    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento não mudou sem a data (continua P2)" ($r.Body -match ">P2<")

    # A esta altura o assistido já frequenta aos domingos (definido logo acima), então a data da
    # 1ª sessão do novo tratamento precisa cair num domingo (item 3) — 2024-03-10 é domingo.
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/tratamento" -Form @{ tratamentoAtualId = "5"; dataPrimeiraSessao = "2024-03-10" }
    Check "Alterar tratamento com data no dia certo funciona" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento atualizado para P3E" ($r.Body -match ">P3E<")

    # 13e2. Consistência de dia da semana (item 3): sessão fora do dia de assistência é bloqueada
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-03-11" }
    Check "Sessão numa segunda-feira é bloqueada (assistido é de domingo)" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-03-17" }
    Check "Sessão no domingo seguinte é aceita" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    # 13e. Exclusão lógica do prontuário (item 1): desativar/reativar e filtro na listagem
    $r = Invoke-CurlForm -Url "$BaseUrl/"
    Check "Assistido ativo aparece na listagem padrão" ($r.Body -match [regex]::Escape($nomeEditado))

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/desativar"
    Check "Desativar prontuário redireciona" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    Check "Assistido inativo some da listagem padrão" ($r.Body -notmatch [regex]::Escape($nomeEditado))

    $r = Invoke-CurlForm -Url "$BaseUrl/?mostrarInativos=true"
    Check "Assistido inativo aparece com filtro 'mostrarInativos'" ($r.Body -match [regex]::Escape($nomeEditado))

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/reativar"
    Check "Reativar prontuário redireciona" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    Check "Assistido reativado volta a aparecer na listagem padrão" ($r.Body -match [regex]::Escape($nomeEditado))

    # 13. Perfil de Trabalhador (item 2: função já selecionável no cadastro)
    $nomeTrabalhador = "SMOKE_TEST_TRAB_$(Get-Date -Format yyyyMMddHHmmssfff)"
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeTrabalhador; vinculo = "TRABALHADOR"; funcoes = "DIRIGENTE" }
    Check "Cadastro de trabalhador redireciona para /" ($r.StatusCode -eq 302)

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    $trabalhadorId = $null
    if ($r.Body -match "$([regex]::Escape($nomeTrabalhador))[\s\S]*?prontuario/(\d+)") { $trabalhadorId = $Matches[1] }
    Check "Assistido-trabalhador aparece na listagem com ID" ($null -ne $trabalhadorId)

    if ($trabalhadorId) {
        $trabalhadorUrl = "$BaseUrl/prontuario/$trabalhadorId"

        $r = Invoke-CurlForm -Url $trabalhadorUrl
        Check "Prontuário mostra função já definida no cadastro" ($r.Body -match "Dirigente")

        $r = Invoke-CurlForm -Url "$trabalhadorUrl/trabalhador"
        Check "Formulário de perfil de trabalhador responde 200" ($r.StatusCode -eq 200)

        $r = Invoke-CurlForm -Method POST -Url "$trabalhadorUrl/trabalhador" -Form @{ funcoes = "PASSISTA" }
        Check "Perfil de trabalhador atualizado pela tela dedicada" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$trabalhadorId$")

        $r = Invoke-CurlForm -Url $trabalhadorUrl
        Check "Prontuário mostra função atualizada (Passista)" ($r.Body -match "Passista")
    }

} finally {
    Write-Output "Limpando dados de teste ($nomeTeste)..."
    Invoke-Sql "DELETE FROM historico_dia_frequencia WHERE assistido_id IN (SELECT id FROM assistido WHERE nome IN ('$nomeTeste', '$nomeEditado'));" | Out-Null
    Invoke-Sql "DELETE FROM entrevista WHERE assistido_id IN (SELECT id FROM assistido WHERE nome IN ('$nomeTeste', '$nomeEditado'));" | Out-Null
    Invoke-Sql "DELETE FROM sessao_tratamento WHERE assistido_id IN (SELECT id FROM assistido WHERE nome IN ('$nomeTeste', '$nomeEditado'));" | Out-Null
    Invoke-Sql "DELETE FROM avaliacao WHERE assistido_id IN (SELECT id FROM assistido WHERE nome IN ('$nomeTeste', '$nomeEditado'));" | Out-Null
    Invoke-Sql "DELETE FROM assistido WHERE nome IN ('$nomeTeste', '$nomeEditado');" | Out-Null
    # trabalhador/trabalhador_funcao têm ON DELETE CASCADE a partir de assistido, não precisam de DELETE próprio.
    Invoke-Sql "DELETE FROM assistido WHERE nome = '$nomeTrabalhador';" | Out-Null
}

Write-Output ""
if ($script:failures.Count -eq 0) {
    Write-Output "TODOS OS TESTES PASSARAM."
    exit 0
} else {
    Write-Output "$($script:failures.Count) TESTE(S) FALHARAM: $($script:failures -join ', ')"
    exit 1
}
