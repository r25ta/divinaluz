<#
.SYNOPSIS
Bateria de testes end-to-end via curl contra o app já rodando (use dev-run.ps1 antes).
Cria e limpa seus próprios dados de teste (prefixo "SMOKE_TEST_"), não requer app parado.

Cobre: login/CSRF, cadastro com 1ª sessão obrigatória em Domingo/Terça, regra semanal de
presença (2ª chegada na semana vira ouvinte), regra das 4 sessões -> Avaliação -> Entrevista
(bloqueio via CartaoStatus), atualização do tratamento pela Entrevista, reinício em P2 após
21 dias de hiato, edição de dados, troca de dia de assistência (com log de auditoria),
Alterar Tratamento pelo prontuário, consistência de dia da semana, exclusão lógica e perfil
de Trabalhador.

Como o app agora exige autenticação (Spring Security), o script faz login como admin e
mantém um cookie jar entre as chamadas — diferente da versão anterior, que não guardava
sessão. Isso também permite conferir mensagens flash (erro/aviso) quando relevante.

Como parte do prontuário (dia de assistência, sessões, função de trabalhador) não é mais
exibida nas telas (ver CLAUDE.md), alguns pontos são conferidos direto no banco via psql
em vez de via HTML.

.OUTPUTS
Imprime PASS/FAIL por verificação e um resumo final. Exit 0 se tudo passou, 1 caso contrário.
#>
param(
    [string]$BaseUrl = "http://localhost:8081/divinaluz",
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\18\bin\psql.exe",
    [string]$DbName = "divinaluz_db",
    [string]$DbUser = "postgres",
    [string]$DbPassword = "admin",
    [string]$AdminLogin = "admin",
    [string]$AdminSenha = "password"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$script:failures = @()
$script:CookieJar = Join-Path $env:TEMP "divinaluz-smoke-cookies-$PID.txt"
$script:CsrfToken = $null
if (Test-Path $script:CookieJar) { Remove-Item $script:CookieJar -Force }

$nomeTeste = "SMOKE_TEST_$(Get-Date -Format yyyyMMddHHmmssfff)"
$nomeEditado = "${nomeTeste}_EDITADO"
$nomeTrabalhador = "SMOKE_TEST_TRAB_$(Get-Date -Format yyyyMMddHHmmssfff)"

function Check([string]$Nome, [bool]$Condicao, [string]$Detalhe = "") {
    if ($Condicao) {
        Write-Output "PASS: $Nome"
    } else {
        Write-Output "FAIL: $Nome $Detalhe"
        $script:failures += $Nome
    }
}

function Extract-Csrf([string]$Body) {
    if ($Body -match 'name="_csrf"\s+value="([^"]+)"') { return $Matches[1] }
    return $null
}

# Todas as chamadas usam o mesmo cookie jar (-b/-c), então a sessão HTTP autenticada é
# mantida do login até o fim do script — diferente da versão anterior deste script.
function Invoke-CurlForm {
    param(
        [string]$Method = "GET",
        [string]$Url,
        [hashtable]$Form = $null
    )
    $curlArgs = @("-s", "-i", "-b", $script:CookieJar, "-c", $script:CookieJar)
    if ($Method -eq "POST") {
        $curlArgs += @("-X", "POST")
        $body = @{}
        if ($Form) { foreach ($k in $Form.Keys) { $body[$k] = $Form[$k] } }
        if (-not $body.ContainsKey("_csrf") -and $script:CsrfToken) { $body["_csrf"] = $script:CsrfToken }
        foreach ($key in $body.Keys) {
            $curlArgs += @("--data-urlencode", "$key=$($body[$key])")
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
    & $PsqlPath -U $DbUser -d $DbName -h localhost -t -A -c $Sql 2>&1
}

function Invoke-SqlScalar([string]$Sql) {
    $out = Invoke-Sql $Sql
    if ($null -eq $out) { return $null }
    (@($out) | Select-Object -First 1).ToString().Trim()
}

try {
    # 0. Login como admin (V21/V23 — ver CLAUDE.md 3.11 sobre o hash real da credencial provisória)
    $loginPage = Invoke-CurlForm -Url "$BaseUrl/login"
    Check "Página de login responde 200" ($loginPage.StatusCode -eq 200)
    $csrfLogin = Extract-Csrf $loginPage.Body
    Check "Token CSRF obtido na página de login" ($null -ne $csrfLogin)
    if (-not $csrfLogin) { throw "Não foi possível obter o token CSRF do /login." }

    $loginResp = Invoke-CurlForm -Method POST -Url "$BaseUrl/login" -Form @{ username = $AdminLogin; password = $AdminSenha; "_csrf" = $csrfLogin }
    Check "Login do admin bem-sucedido (redireciona para /)" ($loginResp.StatusCode -eq 302 -and $loginResp.Location -match '/divinaluz/?$')
    if ($loginResp.StatusCode -ne 302 -or $loginResp.Location -notmatch '/divinaluz/?$') {
        throw "Login do admin falhou (login=$AdminLogin) — abortando smoke test."
    }

    $novoPage = Invoke-CurlForm -Url "$BaseUrl/novo"
    $script:CsrfToken = Extract-Csrf $novoPage.Body
    Check "Token CSRF de sessão obtido para as próximas requisições" ($null -ne $script:CsrfToken)
    if (-not $script:CsrfToken) { throw "Não obteve token CSRF pós-login — abortando." }

    # IDs reais do catálogo (evita depender de IDs fixos do seed V3)
    $p2Id = Invoke-SqlScalar "SELECT id FROM tipo_tratamento WHERE codigo = 'P2';"
    $p3eId = Invoke-SqlScalar "SELECT id FROM tipo_tratamento WHERE codigo = 'P3E';"
    Check "Tratamento P2 encontrado no catálogo" ([bool]$p2Id)
    Check "Tratamento P3E encontrado no catálogo" ([bool]$p3eId)

    # 1. Index responde autenticado
    $r = Invoke-CurlForm -Url "$BaseUrl/"
    Check "Index responde 200 (autenticado)" ($r.StatusCode -eq 200)

    # 2. Cadastro: 1ª sessão obrigatória em Domingo/Terça (item 4). 2024-01-07 é domingo ->
    # define diaFrequencia=DOMINGO_08H, entra em P2, cria a 1ª sessão automaticamente.
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeTeste; vinculo = "ASSISTIDO"; dataPrimeiraSessao = "2024-01-07" }
    Check "Cadastro redireciona para /" ($r.StatusCode -eq 302 -and $r.Location -match '/divinaluz/?$')

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    $assistidoId = $null
    if ($r.Body -match "$([regex]::Escape($nomeTeste))[\s\S]*?prontuario/(\d+)") { $assistidoId = $Matches[1] }
    Check "Assistido de teste aparece na listagem com ID" ($null -ne $assistidoId)
    if (-not $assistidoId) { throw "Não foi possível continuar sem o ID do assistido de teste." }

    $prontuarioUrl = "$BaseUrl/prontuario/$assistidoId"
    $cartaoUrl = "$prontuarioUrl/cartao"

    # 3. Prontuário e cartão mostram o estado inicial (tratamento P2, 1ª sessão já registrada)
    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra tratamento P2" ($r.Body -match ">P2<")

    $r = Invoke-CurlForm -Url $cartaoUrl
    Check "Cartão mostra status 'Em Tratamento'" ($r.Body -match "Em Tratamento")
    Check "Cartão mostra 1 sessão registrada" ($r.Body -match "1<\/strong>\s*<span class=""dl-muted"">\s*sessões registradas" -or $r.Body -match ">1<" )
    Check "Cartão mostra a 1ª sessão como presença efetiva" ($r.Body -match ">1ª<" -and $r.Body -match "Presença efetiva")

    # 4. Troca de dia de assistência no meio da semana (item 3 / rastreabilidade): o assistido
    # começou domingo mas passa a frequentar terça — ainda na mesma semana (dom 07/01 a sáb 13/01).
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/dia-frequencia" -Form @{ diaFrequencia = "TERCA_19H"; motivo = "Mudou de turno no trabalho" }
    Check "Dia de assistência alterado (redireciona)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $diaAtual = Invoke-SqlScalar "SELECT dia_frequencia FROM assistido WHERE id = $assistidoId;"
    Check "Dia de assistência gravado como TERCA_19H" ($diaAtual -eq "TERCA_19H")

    $logDia = Invoke-Sql "SELECT dia_anterior || '|' || dia_novo || '|' || motivo FROM historico_dia_frequencia WHERE assistido_id = $assistidoId ORDER BY id DESC LIMIT 1;"
    Check "Histórico de dia registra a troca com motivo" (($logDia -join '') -match 'DOMINGO_08H\|TERCA_19H\|Mudou de turno no trabalho')

    # 5. Regra semanal de presença: uma 2ª chegada na MESMA semana de assistência vira ouvinte e
    # não bloqueia nem avança o ciclo — 09/01/2024 é terça (já bate com o novo dia) e cai na mesma
    # semana dom-sáb da sessão de 07/01.
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-09" }
    Check "2ª chegada na mesma semana é aceita como ouvinte (não bloqueia)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $ouvinteRow = Invoke-Sql "SELECT ouvinte::text || '|' || COALESCE(numero_serie::text, 'NULL') FROM sessao_tratamento WHERE assistido_id = $assistidoId AND data_consulta = '2024-01-09';"
    Check "Sessão extra gravada como ouvinte, sem número de série" (($ouvinteRow -join '') -match '(?i)^true\|NULL')

    $r = Invoke-CurlForm -Url $cartaoUrl
    Check "Cartão mostra 2 sessões (1 efetiva + 1 ouvinte)" ($r.Body -match "Ouvinte" -and $r.Body -match "Presença efetiva")

    # 6-8. Sessões 2ª, 3ª e 4ª do ciclo, agora sempre nas terças (dia de assistência atual)
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-16" }
    Check "2ª sessão efetiva registrada" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-23" }
    Check "3ª sessão efetiva registrada" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-01-30" }
    Check "4ª sessão efetiva registrada" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    $statusPos4 = Invoke-SqlScalar "SELECT status_cartao FROM assistido WHERE id = $assistidoId;"
    Check "Status do cartão vira 'Aguardando Avaliação' após a 4ª sessão" ($statusPos4 -eq "AGUARDANDO_AVALIACAO")

    # 9. Regra das 4 sessões: 5ª sessão bloqueada pelo status do cartão até Avaliação + Entrevista
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-02-06" }
    Check "5ª sessão bloqueada com cartão 'Aguardando Avaliação'" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    # 10a. Avaliação (diagnóstico) — data livre, não muda tratamento nem destrava sessão sozinha
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/avaliacao" -Form @{ data = "2024-02-06"; evolucao = "MELHOR"; historico = "Historico de teste"; observacoes = "Observacoes de teste" }
    Check "Avaliação registrada (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra observações da avaliação" ($r.Body -match "Observacoes de teste")

    $statusPosAvaliacao = Invoke-SqlScalar "SELECT status_cartao FROM assistido WHERE id = $assistidoId;"
    Check "Status do cartão vira 'Aguardando Entrevista' após a Avaliação" ($statusPosAvaliacao -eq "AGUARDANDO_ENTREVISTA")

    $avaliacaoId = Invoke-SqlScalar "SELECT id FROM avaliacao WHERE assistido_id = $assistidoId ORDER BY id DESC LIMIT 1;"
    Check "ID da avaliação obtido para a entrevista" ([bool]$avaliacaoId)

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-02-06" }
    Check "5ª sessão continua bloqueada só com a Avaliação (falta a Entrevista)" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    # 10b. Entrevista vinculada à avaliação, indicando novo tratamento (P3E): destrava o ciclo,
    # atualiza o tratamento atual e já registra automaticamente a 1ª sessão do novo ciclo.
    if ($avaliacaoId) {
        $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/entrevista?avaliacaoId=$avaliacaoId" -Form @{ data = "2024-02-06"; entrevistador = "Smoke Test"; tratamentoIndicado = $p3eId }
        Check "Entrevista registrada (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")
    }

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento atual atualizado para P3E após entrevista" ($r.Body -match ">P3E<")

    $statusPosEntrevista = Invoke-SqlScalar "SELECT status_cartao || '|' || ciclo_iniciado_em FROM assistido WHERE id = $assistidoId;"
    Check "Status volta para 'Em Tratamento' e ciclo reinicia em 06/02/2024" ($statusPosEntrevista -eq "EM_TRATAMENTO|2024-02-06")

    $r = Invoke-CurlForm -Url $cartaoUrl
    Check "Cartão mostra a 1ª sessão automática do novo ciclo em 06/02/2024" ($r.Body -match "06/02/2024")

    # 11. Reinício automático por ausência de 21+ dias (regra dos 21 dias): próxima sessão 21 dias
    # depois (27/02, terça) deve ser aceita e reiniciar o tratamento em P2, independente do anterior.
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-02-27" }
    Check "Sessão após 21+ dias de hiato é aceita (reinício de ciclo)" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento reiniciado em P2 após hiato de 21+ dias" ($r.Body -match ">P2<")

    $r = Invoke-CurlForm -Url $cartaoUrl
    Check "Cartão mostra nova 1ª sessão do ciclo reiniciado em 27/02/2024" ($r.Body -match "27/02/2024")

    # 12a. Editar dados do assistido: nome e endereço mudam sem afetar o restante. Desde o
    # endereço estruturado (item V15/V16), "residencia" é sempre recalculada a partir de
    # endereco/numero/bairro/cidade/uf (atualizarResidenciaLegada) — enviar "residencia" direto
    # não tem efeito, então o teste agora usa os campos estruturados.
    $r = Invoke-CurlForm -Url "$prontuarioUrl/editar"
    Check "Formulário de edição responde 200" ($r.StatusCode -eq 200)

    # tratamentoAtual precisa ir junto (resubmissão do mesmo P2), senão a edição apagaria o
    # tratamento atual (campo ausente vira null) — mesmo cuidado documentado no item 4.
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/editar" -Form @{ nome = $nomeEditado; vinculo = "ASSISTIDO"; bairro = "Bairro Editado"; cidade = "Cidade Editada"; tratamentoAtual = $p2Id }
    Check "Edição salva (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra nome editado" ($r.Body -match [regex]::Escape($nomeEditado))
    Check "Prontuário mostra residência recalculada a partir do endereço editado" ($r.Body -match "Bairro Editado" -and $r.Body -match "Cidade Editada")
    Check "Tratamento não foi perdido na edição (continua P2)" ($r.Body -match ">P2<")

    # 12b. Alterar Tratamento (card do prontuário) também exige data da 1ª sessão quando muda —
    # agora dá para conferir a mensagem de erro de verdade, porque a sessão HTTP é mantida.
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/tratamento" -Form @{ tratamentoAtualId = $p3eId }
    Check "Alterar tratamento sem data redireciona (com erro)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra o erro pedindo a data da 1ª sessão" ($r.Body -match "Informe a data da 1")
    Check "Tratamento não mudou sem a data (continua P2)" ($r.Body -match ">P2<")

    # Assistido agora frequenta às terças; 05/03/2024 é terça.
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/tratamento" -Form @{ tratamentoAtualId = $p3eId; dataPrimeiraSessao = "2024-03-05" }
    Check "Alterar tratamento com data no dia certo funciona" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento atualizado para P3E" ($r.Body -match ">P3E<")

    # 12c. Consistência de dia da semana (item 3): sessão fora do dia de assistência é bloqueada
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-03-06" }
    Check "Sessão numa quarta-feira é bloqueada (assistido é de terça)" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "2024-03-12" }
    Check "Sessão na terça seguinte é aceita" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    # 13. Exclusão lógica do prontuário (item 1): desativar/reativar e filtro na listagem
    $r = Invoke-CurlForm -Url "$BaseUrl/"
    Check "Assistido ativo aparece na listagem padrão" ($r.Body -match [regex]::Escape($nomeEditado))

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/desativar"
    Check "Desativar prontuário redireciona" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    Check "Assistido inativo some da listagem padrão" ($r.Body -notmatch [regex]::Escape($nomeEditado))

    $r = Invoke-CurlForm -Url "$BaseUrl/?mostrarInativos=true"
    Check "Assistido inativo aparece com filtro 'mostrarInativos'" ($r.Body -match [regex]::Escape($nomeEditado))
    Check "Listagem marca o assistido como Inativo" ($r.Body -match "Inativo")

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/reativar"
    Check "Reativar prontuário redireciona" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    Check "Assistido reativado volta a aparecer na listagem padrão" ($r.Body -match [regex]::Escape($nomeEditado))

    # 14. Perfil de Trabalhador (item 2): cadastro agora também exige a 1ª data de assistência.
    # 2024-01-14 é domingo.
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeTrabalhador; vinculo = "TRABALHADOR"; dataPrimeiraSessao = "2024-01-14"; funcoes = "DIRIGENTE" }
    Check "Cadastro de trabalhador redireciona para /" ($r.StatusCode -eq 302 -and $r.Location -match '/divinaluz/?$')

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    $trabalhadorId = $null
    if ($r.Body -match "$([regex]::Escape($nomeTrabalhador))[\s\S]*?prontuario/(\d+)") { $trabalhadorId = $Matches[1] }
    Check "Assistido-trabalhador aparece na listagem com ID" ($null -ne $trabalhadorId)

    if ($trabalhadorId) {
        $trabalhadorUrl = "$BaseUrl/prontuario/$trabalhadorId"

        $funcaoInicial = Invoke-SqlScalar "SELECT tf.funcao FROM trabalhador_funcao tf JOIN trabalhador t ON t.id = tf.trabalhador_id WHERE t.assistido_id = $trabalhadorId;"
        Check "Função definida já no cadastro (Dirigente)" ($funcaoInicial -eq "DIRIGENTE")

        $r = Invoke-CurlForm -Url "$trabalhadorUrl/trabalhador"
        Check "Formulário de perfil de trabalhador responde 200" ($r.StatusCode -eq 200)

        $r = Invoke-CurlForm -Method POST -Url "$trabalhadorUrl/trabalhador" -Form @{ funcoes = "PASSISTA" }
        Check "Perfil de trabalhador atualizado pela tela dedicada" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$trabalhadorId$")

        $funcaoAtualizada = Invoke-SqlScalar "SELECT tf.funcao FROM trabalhador_funcao tf JOIN trabalhador t ON t.id = tf.trabalhador_id WHERE t.assistido_id = $trabalhadorId;"
        Check "Função atualizada para Passista (Dirigente removida)" ($funcaoAtualizada -eq "PASSISTA")
    }

} finally {
    Write-Output "Limpando dados de teste ($nomeTeste)..."
    # O cadastro de trabalhador agora também exige dataPrimeiraSessao (item 4), então ele também
    # ganha uma sessao_tratamento automática — precisa entrar na limpeza como os demais, senão a
    # FK "fk6c6t2lawkln7d6w90vhg2my0l" (sessao_tratamento -> assistido, sem CASCADE no banco real
    # — ver CLAUDE.md item 6) bloqueia o DELETE FROM assistido.
    $nomes = "'$nomeTeste', '$nomeEditado', '$nomeTrabalhador'"
    Invoke-Sql "DELETE FROM historico_dia_frequencia WHERE assistido_id IN (SELECT id FROM assistido WHERE nome IN ($nomes));" | Out-Null
    Invoke-Sql "DELETE FROM entrevista WHERE assistido_id IN (SELECT id FROM assistido WHERE nome IN ($nomes));" | Out-Null
    Invoke-Sql "DELETE FROM sessao_tratamento WHERE assistido_id IN (SELECT id FROM assistido WHERE nome IN ($nomes));" | Out-Null
    Invoke-Sql "DELETE FROM avaliacao WHERE assistido_id IN (SELECT id FROM assistido WHERE nome IN ($nomes));" | Out-Null
    Invoke-Sql "DELETE FROM assistido WHERE nome IN ($nomes);" | Out-Null
    # trabalhador/trabalhador_funcao têm ON DELETE CASCADE a partir de assistido, não precisam de DELETE próprio.
    if (Test-Path $script:CookieJar) { Remove-Item $script:CookieJar -Force }
}

Write-Output ""
if ($script:failures.Count -eq 0) {
    Write-Output "TODOS OS TESTES PASSARAM."
    exit 0
} else {
    Write-Output "$($script:failures.Count) TESTE(S) FALHARAM: $($script:failures -join ', ')"
    exit 1
}
