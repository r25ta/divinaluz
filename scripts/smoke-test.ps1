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
    [string]$AdminSenha = "JesusCristo"
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
# Preleções criadas pelo teste do check-in por QR code (seção 16).
$temaPrelecaoTeste = "SMOKE_TEST_PRELECAO_HOJE"
$temaPrelecaoAntiga = "SMOKE_TEST_PRELECAO_ANTIGA"

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

# Entra com outro login no cookie jar corrente (zerado antes, para não herdar a sessão anterior).
# As permissões do trabalhador são resolvidas no login (UserDetailsService), então trocar a função
# de alguém só passa a valer quando ele entra de novo — daí este helper ser chamado a cada troca.
function Login-Como([string]$Login, [string]$Senha) {
    if (Test-Path $script:CookieJar) { Remove-Item $script:CookieJar -Force }
    $pagina = Invoke-CurlForm -Url "$BaseUrl/login"
    $csrf = Extract-Csrf $pagina.Body
    $script:CsrfToken = $null
    return Invoke-CurlForm -Method POST -Url "$BaseUrl/login" -Form @{ username = $Login; password = $Senha; "_csrf" = $csrf }
}

# Troca a função de um trabalhador pela sessão do admin e devolve o controle ao jar corrente. O
# CSRF é relido da página porque o token é por sessão HTTP — o do outro jar não serve aqui.
function Definir-Funcao([string]$AdminJar, [string]$AssistidoId, [string]$Funcao) {
    $jarAnterior = $script:CookieJar
    $csrfAnterior = $script:CsrfToken
    $script:CookieJar = $AdminJar
    $pagina = Invoke-CurlForm -Url "$BaseUrl/trabalhadores/$AssistidoId"
    $script:CsrfToken = Extract-Csrf $pagina.Body
    $form = @{}
    if ($Funcao) { $form["funcoes"] = $Funcao }
    Invoke-CurlForm -Method POST -Url "$BaseUrl/trabalhadores/$AssistidoId" -Form $form | Out-Null
    # Segue o redirect para consumir a mensagem flash: duas POSTs seguidas sem o GET empilhariam
    # flash maps para o mesmo destino e o Spring entregaria o mais antigo (ver CLAUDE.md, item 6).
    # A página fica guardada para quem quiser conferir a mensagem desta ação.
    $script:UltimaPaginaTrabalhadores = (Invoke-CurlForm -Url "$BaseUrl/trabalhadores").Body
    $script:CookieJar = $jarAnterior
    $script:CsrfToken = $csrfAnterior
}

# Altera o acesso de um cadastro pela seção "Acesso ao Sistema" da edição (desde 2026-10-03 não há
# mais tela /acesso própria). A edição grava TODOS os dados pessoais do formulário, então o nome e o
# tratamento atual vão junto — sem o tratamento, o select vazio o apagaria. alterarAcesso=true é o
# que a seção do Administrador envia; sem ele o servidor não mexe no acesso. Não consome o flash:
# quem chama decide (para poder conferir a mensagem).
function Salvar-Acesso([string]$AssistidoId, [hashtable]$Acesso) {
    $pagina = Invoke-CurlForm -Url "$BaseUrl/prontuario/$AssistidoId/editar"
    $script:CsrfToken = Extract-Csrf $pagina.Body
    $form = @{
        nome = (Invoke-SqlScalar "SELECT nome FROM assistido WHERE id = $AssistidoId;")
        tratamentoAtual = (Invoke-SqlScalar "SELECT COALESCE(tratamento_atual_id::text, '') FROM assistido WHERE id = $AssistidoId;")
        alterarAcesso = "true"
    }
    foreach ($chave in $Acesso.Keys) { $form[$chave] = $Acesso[$chave] }
    return Invoke-CurlForm -Method POST -Url "$BaseUrl/prontuario/$AssistidoId/editar" -Form $form
}

# Troca o perfil do acesso (só o Administrador pode) pela sessão do admin. Perfil e função são
# coisas diferentes de propósito: o perfil diz se a pessoa é staff, a função diz quais módulos ela
# alcança (ver SecurityConfig.authorities) — daí dar para testar uma sem a outra.
function Definir-Perfil([string]$AdminJar, [string]$AssistidoId, [string]$Login, [string]$Perfil) {
    $jarAnterior = $script:CookieJar
    $csrfAnterior = $script:CsrfToken
    $script:CookieJar = $AdminJar
    Salvar-Acesso $AssistidoId @{ login = $Login; perfil = $Perfil; acessoAtivo = "true" } | Out-Null
    # Mesmo motivo do Definir-Funcao: consome o flash para não empilhar com a POST seguinte.
    Invoke-CurlForm -Url "$BaseUrl/prontuario/$AssistidoId" | Out-Null
    $script:CookieJar = $jarAnterior
    $script:CsrfToken = $csrfAnterior
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
    # define diaFrequencia=DOMINGO_08H, entra em P2, cria a 1ª sessão automaticamente. Sem e-mail,
    # o cadastro exige a senha de acesso (o acesso ASSISTIDO agora é criado automaticamente).
    # 2a. Login e e-mail no cadastro (2026-10-03): sem login, e com o e-mail de outro cadastro, o
    # formulário volta preenchido com o erro — e nada é gravado.
    $emailTeste = "smoke.$PID@exemplo.com"
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeTeste; dataPrimeiraSessao = "07/01/2024"; senhaAcesso = "senha123"; confirmacaoSenhaAcesso = "senha123" }
    Check "Cadastro sem login volta ao formulário com o erro, preenchido" (
        $r.StatusCode -eq 200 -and $r.Body -match "Informe o login" -and $r.Body -match [regex]::Escape($nomeTeste))
    $gravadoSemLogin = Invoke-SqlScalar "SELECT count(*) FROM assistido WHERE nome = '$nomeTeste';"
    Check "Cadastro recusado não grava nada" ($gravadoSemLogin -eq "0")

    $r = Invoke-CurlForm -Url "$BaseUrl/acesso/verificar?nome=$([uri]::EscapeDataString('Joana da Silva Souza'))"
    Check "Verificação sugere o login pelo nome (primeiro e último, sem partícula)" ($r.Body -match '"sugestao":"joana\.souza\d*"')

    $loginCadastro = "smoke.teste$PID"
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeTeste; email = $emailTeste; login = $loginCadastro; dataPrimeiraSessao = "07/01/2024"; senhaAcesso = "senha123"; confirmacaoSenhaAcesso = "senha123" }
    Check "Cadastro redireciona para /" ($r.StatusCode -eq 302 -and $r.Location -match '/divinaluz/?$')

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    $assistidoId = $null
    # Âncora na célula "nome-assistido" da tabela (não em qualquer ocorrência do nome na página —
    # a mensagem flash de sucesso do cadastro, acima da tabela, também repete o nome; sem ancorar
    # na célula, um SMOKE_TEST_ residual de outra execução com link próprio podia ser capturado).
    if ($r.Body -match "nome-assistido""[^>]*>$([regex]::Escape($nomeTeste))<[\s\S]*?prontuario/(\d+)") { $assistidoId = $Matches[1] }
    Check "Assistido de teste aparece na listagem com ID" ($null -ne $assistidoId)
    if (-not $assistidoId) { throw "Não foi possível continuar sem o ID do assistido de teste." }
    Check "Listagem mostra a mensagem de sucesso do cadastro com o login gerado" ($r.Body -match "Login de acesso")

    # 2c. Formulário aberto antes de um novo login no mesmo navegador (outra aba, ou relogin depois de
    # um deploy): o token CSRF é da sessão anterior. Continua 403 — é proteção —, mas com uma página
    # que explica e diz que nada foi gravado, no lugar da Whitelabel (2026-10-04).
    $tokenAntigo = Extract-Csrf (Invoke-CurlForm -Url "$BaseUrl/novo").Body
    $paginaLogin = Invoke-CurlForm -Url "$BaseUrl/login"
    $script:CsrfToken = $null
    Invoke-CurlForm -Method POST -Url "$BaseUrl/login" -Form @{ username = $AdminLogin; password = $AdminSenha; "_csrf" = (Extract-Csrf $paginaLogin.Body) } | Out-Null
    $script:CsrfToken = $tokenAntigo
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = "$nomeTeste ANTIGO" }
    Check "Formulário de antes de um novo login dá 403 explicando que a página ficou desatualizada" (
        $r.StatusCode -eq 403 -and $r.Body -match "ficou desatualizada" -and $r.Body -match "nada foi gravado")
    $script:CsrfToken = Extract-Csrf (Invoke-CurlForm -Url "$BaseUrl/").Body

    # 2b. O mesmo e-mail (com outra caixa) e o mesmo login (com outra caixa) não entram num segundo
    # cadastro — a mensagem diz de quem é o e-mail, para a recepção não duplicar a pessoa.
    $nomeDuplicado = "$nomeTeste DUP"
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeDuplicado; email = $emailTeste.ToUpper(); login = "smoke.outro$PID"; dataPrimeiraSessao = "07/01/2024"; senhaAcesso = "senha123"; confirmacaoSenhaAcesso = "senha123" }
    Check "E-mail já usado (outra caixa) é recusado dizendo de quem é" (
        $r.StatusCode -eq 200 -and $r.Body -match "já está no cadastro de" -and $r.Body -match [regex]::Escape($nomeTeste))
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeDuplicado; login = $loginCadastro.ToUpper(); dataPrimeiraSessao = "07/01/2024"; senhaAcesso = "senha123"; confirmacaoSenhaAcesso = "senha123" }
    Check "Login já usado (outra caixa) é recusado" ($r.StatusCode -eq 200 -and $r.Body -match "já está em uso")
    $duplicados = Invoke-SqlScalar "SELECT count(*) FROM assistido WHERE nome = '$nomeDuplicado';"
    Check "Nenhum cadastro duplicado foi gravado" ($duplicados -eq "0")

    $r = Invoke-CurlForm -Url "$BaseUrl/acesso/verificar?email=$([uri]::EscapeDataString($emailTeste))"
    Check "Verificação avisa na hora que o e-mail já está em outro cadastro" ($r.Body -match '"emailEmUso"' -and $r.Body -match [regex]::Escape($nomeTeste))
    $r = Invoke-CurlForm -Url "$BaseUrl/acesso/verificar?login=$loginCadastro"
    Check "Verificação avisa que o login já está em uso" ($r.Body -match "já está em uso")

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
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "09/01/2024" }
    Check "2ª chegada na mesma semana é aceita como ouvinte (não bloqueia)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $ouvinteRow = Invoke-Sql "SELECT ouvinte::text || '|' || COALESCE(numero_serie::text, 'NULL') FROM sessao_tratamento WHERE assistido_id = $assistidoId AND data_consulta = '2024-01-09';"
    Check "Sessão extra gravada como ouvinte, sem número de série" (($ouvinteRow -join '') -match '(?i)^true\|NULL')

    $r = Invoke-CurlForm -Url $cartaoUrl
    Check "Cartão mostra 2 sessões (1 efetiva + 1 ouvinte)" ($r.Body -match "Ouvinte" -and $r.Body -match "Presença efetiva")

    # 6-8. Sessões 2ª, 3ª e 4ª do ciclo, agora sempre nas terças (dia de assistência atual)
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "16/01/2024" }
    Check "2ª sessão efetiva registrada" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "23/01/2024" }
    Check "3ª sessão efetiva registrada" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "30/01/2024" }
    Check "4ª sessão efetiva registrada" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    $statusPos4 = Invoke-SqlScalar "SELECT status_cartao FROM assistido WHERE id = $assistidoId;"
    Check "Status do cartão vira 'Aguardando Avaliação' após a 4ª sessão" ($statusPos4 -eq "AGUARDANDO_AVALIACAO")

    # 8b. Módulo de Entrevista (item 4): a fila /entrevistas reúne quem está com o cartão retido e
    # aponta pro mesmo formulário de sempre; o prontuário ganha o mesmo atalho.
    $r = Invoke-CurlForm -Url "$BaseUrl/entrevistas"
    Check "Fila de Entrevista lista o assistido em Aguardando Avaliação" ($r.Body -match [regex]::Escape($nomeTeste) -and $r.Body -match "prontuario/$assistidoId/nova-avaliacao")
    Check "Navbar tem o link do Módulo de Entrevista" ($r.Body -match "bi-chat-square-text")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra o status 'Aguardando Avaliação' e o botão de registrar" ($r.Body -match "Aguardando Avaliação" -and $r.Body -match "prontuario/$assistidoId/nova-avaliacao")

    # 9. Regra das 4 sessões: 5ª sessão bloqueada pelo status do cartão até Avaliação + Entrevista
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "06/02/2024" }
    Check "5ª sessão bloqueada com cartão 'Aguardando Avaliação'" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    # 10a. Avaliação (diagnóstico) — data livre, não muda tratamento nem destrava sessão sozinha
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/avaliacao" -Form @{ data = "06/02/2024"; evolucao = "MELHOR"; historico = "Historico de teste"; observacoes = "Observacoes de teste" }
    Check "Avaliação registrada (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra observações da avaliação" ($r.Body -match "Observacoes de teste")

    $statusPosAvaliacao = Invoke-SqlScalar "SELECT status_cartao FROM assistido WHERE id = $assistidoId;"
    Check "Status do cartão vira 'Aguardando Entrevista' após a Avaliação" ($statusPosAvaliacao -eq "AGUARDANDO_ENTREVISTA")

    $avaliacaoId = Invoke-SqlScalar "SELECT id FROM avaliacao WHERE assistido_id = $assistidoId ORDER BY id DESC LIMIT 1;"
    Check "ID da avaliação obtido para a entrevista" ([bool]$avaliacaoId)

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "06/02/2024" }
    Check "5ª sessão continua bloqueada só com a Avaliação (falta a Entrevista)" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    # Módulo de Entrevista: agora a fila e o prontuário devem apontar para a entrevista, com o
    # avaliacaoId certo — a mesma avaliação recém-registrada. O check-in também oferece o atalho
    # (a recepção pode encaminhar direto da tela do scan, mesmo sem sessão aberta no momento).
    $r = Invoke-CurlForm -Url "$BaseUrl/entrevistas"
    Check "Fila de Entrevista lista o assistido em Aguardando Entrevista com o link certo" ($r.Body -match [regex]::Escape($nomeTeste) -and $r.Body -match "prontuario/$assistidoId/nova-entrevista\?avaliacaoId=$avaliacaoId")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário mostra o botão de Registrar Entrevista com o avaliacaoId certo" ($r.Body -match "prontuario/$assistidoId/nova-entrevista\?avaliacaoId=$avaliacaoId")

    $codigoCartaoEntrevista = Invoke-SqlScalar "SELECT codigo_cartao FROM assistido WHERE id = $assistidoId;"
    if ($codigoCartaoEntrevista) {
        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/$codigoCartaoEntrevista"
        Check "Tela de check-in oferece Registrar Entrevista com o avaliacaoId certo" ($r.Body -match "prontuario/$assistidoId/nova-entrevista\?avaliacaoId=$avaliacaoId")
    }

    # 10b. Entrevista vinculada à avaliação, indicando novo tratamento (P3E): destrava o ciclo,
    # atualiza o tratamento atual e já registra automaticamente a 1ª sessão do novo ciclo.
    if ($avaliacaoId) {
        $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/entrevista?avaliacaoId=$avaliacaoId" -Form @{ data = "06/02/2024"; entrevistador = "Smoke Test"; tratamentoIndicado = $p3eId }
        Check "Entrevista registrada (redireciona para prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")
    }

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento atual atualizado para P3E após entrevista" ($r.Body -match ">P3E<")

    $statusPosEntrevista = Invoke-SqlScalar "SELECT status_cartao || '|' || ciclo_iniciado_em FROM assistido WHERE id = $assistidoId;"
    Check "Status volta para 'Em Tratamento' e ciclo reinicia em 06/02/2024" ($statusPosEntrevista -eq "EM_TRATAMENTO|2024-02-06")

    $r = Invoke-CurlForm -Url "$BaseUrl/entrevistas"
    Check "Fila de Entrevista não lista mais o assistido após a entrevista" ($r.Body -notmatch "prontuario/$assistidoId/nova-avaliacao" -and $r.Body -notmatch "prontuario/$assistidoId/nova-entrevista")

    $r = Invoke-CurlForm -Url $cartaoUrl
    Check "Cartão mostra a 1ª sessão automática do novo ciclo em 06/02/2024" ($r.Body -match "06/02/2024")

    # 10c. Histórico de cartões: a entrevista encerra o ciclo retido como CONCLUÍDO, guardando o
    # tratamento ANTIGO (P2) — o P3E indicado só vale do ciclo novo em diante.
    $cartaoConcluido = Invoke-SqlScalar "SELECT c.status_final || '|' || t.codigo || '|' || c.sessoes_efetivas FROM cartao_encerrado c LEFT JOIN tipo_tratamento t ON t.id = c.tratamento_id WHERE c.assistido_id = $assistidoId ORDER BY c.id DESC LIMIT 1;"
    Check "Entrevista encerra o cartão anterior como 'Concluído' em P2 com 4 presenças" ($cartaoConcluido -eq "CONCLUIDO|P2|4")

    $r = Invoke-CurlForm -Url $cartaoUrl
    Check "Cartão mostra a seção 'Tratamentos Anteriores' com o ciclo concluído" ($r.Body -match "Tratamentos Anteriores" -and $r.Body -match "Tratamento Concluído")

    # 11. Cartão expirado por ausência de 21+ dias: a sessão (27/02, terça) é barrada, o cartão vira
    # INCOMPLETO_POR_TEMPO e só a confirmação da recepção (reiniciarP2=true) reinicia em P2.
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "27/02/2024" }
    Check "Sessão após 21+ dias sem confirmação é barrada" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    $statusExpirado = Invoke-SqlScalar "SELECT status_cartao FROM assistido WHERE id = $assistidoId;"
    Check "Cartão fica 'Incompleto por Tempo'" ($statusExpirado -eq "INCOMPLETO_POR_TEMPO")

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao?reiniciarP2=true" -Form @{ dataConsulta = "27/02/2024" }
    Check "Recepção confirma o reinício em P2 e a sessão é aceita" ($r.StatusCode -eq 302 -and $r.Location -notmatch "nova-sessao")

    $statusReiniciado = Invoke-SqlScalar "SELECT status_cartao FROM assistido WHERE id = $assistidoId;"
    Check "Status volta para 'Em Tratamento' após o reinício" ($statusReiniciado -eq "EM_TRATAMENTO")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento reiniciado em P2 após hiato de 21+ dias" ($r.Body -match ">P2<")

    $r = Invoke-CurlForm -Url $cartaoUrl
    Check "Cartão mostra nova 1ª sessão do ciclo reiniciado em 27/02/2024" ($r.Body -match "27/02/2024")

    # 11b. O ciclo que expirou não some: vira um cartão encerrado como "Tratamento Incompleto"
    # (decisão do item 1, 2026-09-28), com o tratamento que valia (P3E) e o período do ciclo.
    $cartaoIncompleto = Invoke-SqlScalar "SELECT c.status_final || '|' || t.codigo || '|' || c.iniciado_em FROM cartao_encerrado c LEFT JOIN tipo_tratamento t ON t.id = c.tratamento_id WHERE c.assistido_id = $assistidoId ORDER BY c.id DESC LIMIT 1;"
    Check "Reinício por tempo encerra o cartão como 'Tratamento Incompleto' em P3E" ($cartaoIncompleto -eq "INCOMPLETO_POR_TEMPO|P3E|2024-02-06")

    Check "Cartão mostra o ciclo encerrado como 'Tratamento Incompleto'" ($r.Body -match "Tratamento Incompleto")

    $totalEncerrados = Invoke-SqlScalar "SELECT count(*) FROM cartao_encerrado WHERE assistido_id = $assistidoId;"
    Check "Os dois ciclos encerrados estão no histórico" ($totalEncerrados -eq "2")

    # 12a. Editar dados do assistido: nome e endereço mudam sem afetar o restante. Desde o
    # endereço estruturado (item V15/V16), "residencia" é sempre recalculada a partir de
    # endereco/numero/bairro/cidade/uf (atualizarResidenciaLegada) — enviar "residencia" direto
    # não tem efeito, então o teste agora usa os campos estruturados.
    $r = Invoke-CurlForm -Url "$prontuarioUrl/editar"
    Check "Formulário de edição responde 200" ($r.StatusCode -eq 200)

    # tratamentoAtual precisa ir junto (resubmissão do mesmo P2), senão a edição apagaria o
    # tratamento atual (campo ausente vira null) — mesmo cuidado documentado no item 4.
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/editar" -Form @{ nome = $nomeEditado; bairro = "Bairro Editado"; cidade = "Cidade Editada"; tratamentoAtual = $p2Id }
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
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/tratamento" -Form @{ tratamentoAtualId = $p3eId; dataPrimeiraSessao = "05/03/2024" }
    Check "Alterar tratamento com data no dia certo funciona" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId$")

    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Tratamento atualizado para P3E" ($r.Body -match ">P3E<")

    # 12c. Consistência de dia da semana (item 3): sessão fora do dia de assistência é bloqueada
    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "06/03/2024" }
    Check "Sessão numa quarta-feira é bloqueada (assistido é de terça)" ($r.StatusCode -eq 302 -and $r.Location -match "nova-sessao")

    $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/sessao" -Form @{ dataConsulta = "12/03/2024" }
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

    # 14. O cadastro (item /novo) já cria o acesso automaticamente (perfil ASSISTIDO). Sem e-mail,
    # o login é gerado a partir do nome (fácil memorização) e a senha é a informada no form
    # (senhaAcesso, na etapa 2) — não existe mais o estado "Sem acesso" logo após o cadastro.
    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário já mostra o acesso criado no cadastro (sem 'Sem acesso'/'Criar Acesso')" ($r.Body -notmatch "Sem acesso" -and $r.Body -match "Editar Acesso")

    # Âncora em "Acesso ao Cartão" (não em qualquer "dl-badge-success" — o badge "Ativo" do
    # cadastro usa a mesma classe e aparece antes na página).
    $loginTeste = $null
    if ($r.Body -match 'Acesso ao Cartão[\s\S]*?dl-badge-success"[^>]*>([^<]+)<') { $loginTeste = $Matches[1].Trim() }
    Check "Login gerado a partir do nome extraído do prontuário" ($null -ne $loginTeste)

    $r = Invoke-CurlForm -Url "$prontuarioUrl/acesso"
    Check "A rota antiga /acesso leva à seção de acesso da edição do cadastro" ($r.StatusCode -eq 302 -and $r.Location -match "editar#acesso$")
    $r = Invoke-CurlForm -Url "$prontuarioUrl/editar"
    Check "Edição do cadastro traz o acesso editável para o Administrador" (
        $r.Body -match "Acesso ao Sistema" -and $r.Body -match 'name="alterarAcesso"' -and $r.Body -match [regex]::Escape($loginTeste))
    Check "Login extraído é o informado no cadastro" ($loginTeste -eq $loginCadastro)

    # Confere de ponta a ponta que o login criado funciona e respeita o limite do perfil Assistido
    # (só o próprio cartão) — troca temporariamente para uma sessão HTTP separada do admin.
    $adminCookieJar = $script:CookieJar
    $adminCsrfToken = $script:CsrfToken
    $script:CookieJar = Join-Path $env:TEMP "divinaluz-smoke-cookies-assistido-$PID.txt"
    if (Test-Path $script:CookieJar) { Remove-Item $script:CookieJar -Force }
    try {
        $assistidoLoginPage = Invoke-CurlForm -Url "$BaseUrl/login"
        $csrfAssistido = Extract-Csrf $assistidoLoginPage.Body
        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/login" -Form @{ username = $loginTeste; password = "senha123"; "_csrf" = $csrfAssistido }
        Check "Login do assistido criado funciona e redireciona para o próprio cartão" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$assistidoId/cartao$")

        $r = Invoke-CurlForm -Url $cartaoUrl
        Check "Assistido logado consegue ver o próprio cartão" ($r.StatusCode -eq 200)
        # Pedido do item 1: o assistido vê o cartão atual E os tratamentos já finalizados.
        Check "Assistido vê os próprios tratamentos anteriores no cartão" ($r.Body -match "Tratamentos Anteriores" -and $r.Body -match "Tratamento Incompleto")

        $r = Invoke-CurlForm -Url "$BaseUrl/"
        Check "Assistido logado é bloqueado na listagem geral (só vê o próprio cartão)" ($r.StatusCode -eq 403)

        # Perfil Assistido é somente consulta: cartão + escala de preleções, sem incluir/alterar/excluir.
        $r = Invoke-CurlForm -Url $cartaoUrl
        $script:CsrfToken = Extract-Csrf $r.Body
        Check "Cartão do assistido não oferece botões de staff (Trabalhadores/Novo Cadastro)" ($r.Body -notmatch "Novo Cadastro" -and $r.Body -notmatch "bi-people")

        $r = Invoke-CurlForm -Url "$BaseUrl/prelecao"
        Check "Assistido consegue consultar a escala de preleções (200)" ($r.StatusCode -eq 200)
        Check "Escala de preleções não mostra 'Nova Preleção' nem coluna de ações ao assistido" ($r.Body -notmatch "Nova Preleção" -and $r.Body -notmatch "bi-trash")

        $r = Invoke-CurlForm -Url "$BaseUrl/prelecao/novo"
        Check "Assistido não acessa o formulário de nova preleção (403)" ($r.StatusCode -eq 403)
        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/prelecao/salvar" -Form @{ tema = "x"; dataApresentacao = "07/01/2024" }
        Check "Assistido não consegue incluir preleção (403)" ($r.StatusCode -eq 403)
        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/prelecao/1/excluir"
        Check "Assistido não consegue excluir preleção (403)" ($r.StatusCode -eq 403)
        # O QR é o próprio cartão: o assistido carrega o dele, mas não o de outra pessoa.
        $r = Invoke-CurlForm -Url "$BaseUrl/sessao"
        Check "Assistido não acessa o Módulo Sessão (403)" ($r.StatusCode -eq 403)
        $r = Invoke-CurlForm -Url "$cartaoUrl/qrcode.png"
        Check "Assistido carrega o QR do próprio cartão (200)" ($r.StatusCode -eq 200 -and $r.Body -match "image/png")
        $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/1/cartao/qrcode.png"
        Check "Assistido não carrega o QR do cartão de outra pessoa (403)" ($r.StatusCode -eq 403)

        $r = Invoke-CurlForm -Url "$prontuarioUrl/editar"
        Check "Assistido não acessa a edição do cadastro (403)" ($r.StatusCode -eq 403)
        $r = Invoke-CurlForm -Method POST -Url "$prontuarioUrl/editar" -Form @{ nome = "HACK" }
        Check "Assistido não consegue alterar o cadastro (403)" ($r.StatusCode -eq 403)

        # Regra 4 do cartão: com o cartão retido (Aguardando Avaliação / Aguardando Entrevista) o
        # assistido perde a visibilidade do conteúdo e vê apenas os próprios dados e o status. O
        # status é posicionado direto no banco porque aqui o ciclo já foi liberado pela entrevista.
        Invoke-Sql "UPDATE assistido SET status_cartao = 'AGUARDANDO_AVALIACAO' WHERE id = $assistidoId;" | Out-Null
        $r = Invoke-CurlForm -Url $cartaoUrl
        Check "Cartão 'Aguardando Avaliação' ainda abre para o assistido (200)" ($r.StatusCode -eq 200)
        Check "Cartão retido mostra o nome e o status ao assistido" ($r.Body -match [regex]::Escape($nomeEditado) -and $r.Body -match "Aguardando Avaliação")
        Check "Cartão retido esconde as marcações de presença" ($r.Body -notmatch "dl-marcacao")
        Check "Cartão retido esconde o histórico de presenças" ($r.Body -notmatch "Histórico de Presenças")
        Check "Cartão retido esconde o tratamento atual" ($r.Body -notmatch "Tratamento atual")
        Check "Cartão retido avisa que não há marcação de presença" ($r.Body -match "não há marcação de presença")

        Invoke-Sql "UPDATE assistido SET status_cartao = 'AGUARDANDO_ENTREVISTA' WHERE id = $assistidoId;" | Out-Null
        $r = Invoke-CurlForm -Url $cartaoUrl
        Check "Cartão 'Aguardando Entrevista' também fica retido para o assistido" ($r.StatusCode -eq 200 -and $r.Body -match "Aguardando Entrevista" -and $r.Body -notmatch "dl-marcacao")
        Check "Cartão retido orienta o assistido a procurar a entrevista" ($r.Body -match "Procure a recepção para a entrevista")

        Invoke-Sql "UPDATE assistido SET status_cartao = 'EM_TRATAMENTO' WHERE id = $assistidoId;" | Out-Null
        $r = Invoke-CurlForm -Url $cartaoUrl
        Check "Cartão volta a mostrar marcações e histórico com 'Em Tratamento'" ($r.Body -match "dl-marcacao" -and $r.Body -match "Histórico de Presenças")
    } finally {
        if (Test-Path $script:CookieJar) { Remove-Item $script:CookieJar -Force }
        $script:CookieJar = $adminCookieJar
        $script:CsrfToken = $adminCsrfToken
    }

    # Regra 5: o cartão retido esconde o conteúdo do assistido, mas o staff (recepção/entrevistador)
    # continua vendo tudo para poder encaminhar a pessoa.
    Invoke-Sql "UPDATE assistido SET status_cartao = 'AGUARDANDO_ENTREVISTA' WHERE id = $assistidoId;" | Out-Null
    $r = Invoke-CurlForm -Url $cartaoUrl
    Check "Staff vê marcações e histórico mesmo com o cartão 'Aguardando Entrevista'" ($r.Body -match "dl-marcacao" -and $r.Body -match "Histórico de Presenças")
    Check "Staff vê o tratamento atual mesmo com o cartão retido" ($r.Body -match "Tratamento atual")

    # Sem avaliação pendente (a única já tem entrevista, registrada acima), o atalho "Registrar
    # Entrevista" some — evita apontar para um avaliacaoId que não existe.
    $r = Invoke-CurlForm -Url $prontuarioUrl
    Check "Prontuário não oferece 'Registrar Entrevista' sem avaliação pendente" ($r.Body -notmatch "Registrar Entrevista")
    $codigoCartaoStaff = Invoke-SqlScalar "SELECT codigo_cartao FROM assistido WHERE id = $assistidoId;"
    if ($codigoCartaoStaff) {
        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/$codigoCartaoStaff"
        Check "Check-in não oferece 'Registrar Entrevista' sem avaliação pendente" ($r.Body -notmatch "Registrar Entrevista")
    }

    Invoke-Sql "UPDATE assistido SET status_cartao = 'EM_TRATAMENTO' WHERE id = $assistidoId;" | Out-Null

    # 15. Módulo "Cadastrar Trabalhador" (/trabalhadores): todo trabalhador é antes um assistido,
    # então o cadastro nasce como ASSISTIDO (a categoria Trabalhador saiu do form) e a promoção é
    # um passo à parte, dando perfis de trabalho. 2024-01-14 é domingo.
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/salvar" -Form @{ nome = $nomeTrabalhador; login = "smoke.trabcad$PID"; dataPrimeiraSessao = "14/01/2024"; senhaAcesso = "senha123"; confirmacaoSenhaAcesso = "senha123" }
    Check "Cadastro redireciona para / (futuro trabalhador)" ($r.StatusCode -eq 302 -and $r.Location -match '/divinaluz/?$')

    $r = Invoke-CurlForm -Url "$BaseUrl/"
    $trabalhadorId = $null
    if ($r.Body -match "nome-assistido""[^>]*>$([regex]::Escape($nomeTrabalhador))<[\s\S]*?prontuario/(\d+)") { $trabalhadorId = $Matches[1] }
    Check "Assistido aparece na listagem com ID" ($null -ne $trabalhadorId)

    if ($trabalhadorId) {
        $vinculoInicial = Invoke-SqlScalar "SELECT vinculo FROM assistido WHERE id = $trabalhadorId;"
        Check "Cadastro nasce como ASSISTIDO (categoria Trabalhador saiu do formulário)" ($vinculoInicial -eq "ASSISTIDO")

        $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores"
        Check "Módulo 'Cadastrar Trabalhador' responde 200" ($r.StatusCode -eq 200 -and $r.Body -match "Cadastrar Trabalhador")

        $r = Invoke-CurlForm -Url "$BaseUrl/usuarios"
        Check "Rota antiga /usuarios redireciona para o novo módulo" ($r.StatusCode -eq 302 -and $r.Location -match "trabalhadores$")

        # A busca só retorna assistidos já cadastrados — é por ela que se escolhe quem promover.
        $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores?busca=SMOKE_TEST_TRAB"
        Check "Busca do módulo encontra o assistido para promover" ($r.Body -match [regex]::Escape($nomeTrabalhador) -and $r.Body -match "Tornar Trabalhador")

        $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores/$trabalhadorId"
        Check "Formulário de perfis de trabalho responde 200" ($r.StatusCode -eq 200 -and $r.Body -match "Perfis de Trabalho")
        Check "Formulário oferece os perfis novos (Recepcionista/Entrevistador/Secretária)" ($r.Body -match "func-RECEPCIONISTA" -and $r.Body -match "func-ENTREVISTADOR" -and $r.Body -match "func-SECRETARIA")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/trabalhadores/$trabalhadorId" -Form @{ funcoes = "ENTREVISTADOR" }
        Check "Promover a trabalhador redireciona para o módulo" ($r.StatusCode -eq 302 -and $r.Location -match "trabalhadores$")

        $perfilPromovido = Invoke-SqlScalar "SELECT a.vinculo || '|' || tf.funcao FROM assistido a JOIN trabalhador t ON t.assistido_id = a.id JOIN trabalhador_funcao tf ON tf.trabalhador_id = t.id WHERE a.id = $trabalhadorId;"
        Check "Assistido evoluiu para TRABALHADOR com o perfil Entrevistador" ($perfilPromovido -eq "TRABALHADOR|ENTREVISTADOR")

        $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores"
        Check "Trabalhador aparece na lista do módulo com o perfil" ($r.Body -match [regex]::Escape($nomeTrabalhador) -and $r.Body -match "Entrevistador")

        # Editar os dados cadastrais não pode rebaixar quem já é trabalhador (o vínculo não vem
        # mais do formulário).
        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/prontuario/$trabalhadorId/editar" -Form @{ nome = $nomeTrabalhador; bairro = "Bairro X" }
        $vinculoPosEdicao = Invoke-SqlScalar "SELECT vinculo FROM assistido WHERE id = $trabalhadorId;"
        Check "Editar o cadastro não rebaixa o trabalhador para assistido" ($vinculoPosEdicao -eq "TRABALHADOR")

        # Despromover: sem nenhum perfil marcado ele volta a ser só assistido.
        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/trabalhadores/$trabalhadorId" -Form @{}
        $vinculoDespromovido = Invoke-SqlScalar "SELECT vinculo FROM assistido WHERE id = $trabalhadorId;"
        Check "Sem nenhum perfil marcado, volta a ser somente assistido" ($vinculoDespromovido -eq "ASSISTIDO")

        # ...e volta a ser trabalhador (Passista) para o teste do preletor da sessão, adiante.
        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/trabalhadores/$trabalhadorId" -Form @{ funcoes = "PASSISTA" }
        $funcaoAtualizada = Invoke-SqlScalar "SELECT tf.funcao FROM trabalhador_funcao tf JOIN trabalhador t ON t.id = tf.trabalhador_id WHERE t.assistido_id = $trabalhadorId;"
        Check "Perfil atualizado para Passista (Entrevistador removido)" ($funcaoAtualizada -eq "PASSISTA")
    }

    # 15d. Visibilidade por perfil de trabalho (CLAUDE.md 3.7): é a FUNÇÃO do trabalhador, e não o
    # perfil de acesso, que decide os módulos que ele alcança. O admin recebe todas as permissões,
    # então a matriz só se observa com um login de perfil TRABALHADOR — criado aqui pelo próprio
    # formulário de acesso. Cada troca de função reentra no sistema (ver Login-Como).
    if ($trabalhadorId) {
        $loginTrab = "smoke.trab.$PID"
        $senhaTrab = "senha123"

        $r = Salvar-Acesso $trabalhadorId @{
            login = $loginTrab; senha = $senhaTrab; confirmacaoSenha = $senhaTrab
            perfil = "TRABALHADOR"; acessoAtivo = "true"
        }
        Check "Acesso de perfil TRABALHADOR criado para testar a matriz" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$trabalhadorId$")
        Invoke-CurlForm -Url "$BaseUrl/prontuario/$trabalhadorId" | Out-Null  # consome o flash

        $adminJarMatriz = $script:CookieJar
        $adminCsrfMatriz = $script:CsrfToken
        $script:CookieJar = Join-Path $env:TEMP "divinaluz-smoke-cookies-trab-$PID.txt"
        try {
            # Passista: somente CONSULTA — abre prontuários, mas não cadastra, não atende na sessão,
            # não entrevista, não monta a escala e não promove trabalhadores.
            $r = Login-Como $loginTrab $senhaTrab
            Check "Login do trabalhador Passista funciona" ($r.StatusCode -eq 302)

            $r = Invoke-CurlForm -Url "$BaseUrl/"
            Check "Passista consulta a listagem de assistidos (200)" ($r.StatusCode -eq 200)
            Check "Navbar do Passista esconde Sessão, Entrevistas, Trabalhadores e Novo Cadastro" (
                $r.Body -notmatch 'href="[^"]*/sessao"' -and $r.Body -notmatch 'href="[^"]*/entrevistas"' -and
                $r.Body -notmatch 'href="[^"]*/trabalhadores"' -and $r.Body -notmatch "Novo Cadastro")

            $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/$assistidoId"
            Check "Passista abre o prontuário de outro assistido (200)" ($r.StatusCode -eq 200)
            Check "Prontuário não oferece ao Passista editar dados nem alterar tratamento" (
                $r.Body -notmatch "Editar Dados" -and $r.Body -notmatch "Alterar Tratamento")

            $r = Invoke-CurlForm -Url "$BaseUrl/novo"
            Check "Passista não cadastra assistido (403)" ($r.StatusCode -eq 403)
            Check "O 403 de permissão explica em vez da Whitelabel" (
                $r.Body -match "não tem permissão" -and $r.Body -notmatch "Whitelabel")
            $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/$assistidoId/editar"
            Check "Passista não edita o cadastro de ninguém (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/sessao"
            Check "Passista não alcança o Módulo Sessão (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/entrevistas"
            Check "Passista não alcança a fila de entrevistas (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/$assistidoId/nova-avaliacao"
            Check "Passista não registra avaliação (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores"
            Check "Passista não alcança 'Cadastrar Trabalhador' (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/prelecao/novo"
            Check "Passista não monta a escala de preleções (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/prelecao"
            Check "Passista consulta a escala de preleções (200)" ($r.StatusCode -eq 200)

            # Secretária: ganha CADASTRO, SESSAO e PRELECAO, mas continua sem entrevistar nem
            # promover trabalhadores — é o que diferencia a matriz de um "staff tudo ou nada".
            Definir-Funcao $adminJarMatriz $trabalhadorId "SECRETARIA"
            Login-Como $loginTrab $senhaTrab | Out-Null

            $r = Invoke-CurlForm -Url "$BaseUrl/prelecao/novo"
            Check "Secretária monta a escala de preleções (200)" ($r.StatusCode -eq 200)
            $r = Invoke-CurlForm -Url "$BaseUrl/novo"
            Check "Secretária cadastra assistido (200)" ($r.StatusCode -eq 200)
            $r = Invoke-CurlForm -Url "$BaseUrl/sessao"
            Check "Secretária alcança o Módulo Sessão (200)" ($r.StatusCode -eq 200)
            $r = Invoke-CurlForm -Url "$BaseUrl/entrevistas"
            Check "Secretária ainda não entrevista (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores"
            Check "Secretária não promove trabalhadores (403)" ($r.StatusCode -eq 403)

            # Dirigente: única função que alcança todos os módulos.
            Definir-Funcao $adminJarMatriz $trabalhadorId "DIRIGENTE"
            Login-Como $loginTrab $senhaTrab | Out-Null

            $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores"
            Check "Dirigente alcança 'Cadastrar Trabalhador' (200)" ($r.StatusCode -eq 200)
            $r = Invoke-CurlForm -Url "$BaseUrl/entrevistas"
            Check "Dirigente alcança a fila de entrevistas (200)" ($r.StatusCode -eq 200)

            # Educador de Evangelização: nenhuma permissão — entra, mas só alcança o próprio cartão.
            Definir-Funcao $adminJarMatriz $trabalhadorId "EDUCADOR_EVANGELIZACAO"
            Login-Como $loginTrab $senhaTrab | Out-Null

            $r = Invoke-CurlForm -Url "$BaseUrl/"
            Check "Educador de Evangelização não consulta a listagem (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/$assistidoId/cartao"
            Check "Educador de Evangelização não vê o cartão de outro (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/$trabalhadorId/cartao"
            Check "Educador de Evangelização vê o próprio cartão (200)" ($r.StatusCode -eq 200)

            # A outra ponta da separação perfil x função: é o perfil do acesso que torna a pessoa
            # staff, então função de Dirigente com perfil ASSISTIDO não libera módulo nenhum. Desde
            # 2026-10-03 a tela do trabalhador não chega mais a esse estado (o perfil acompanha a
            # função), mas o Administrador ainda chega nele pelo /prontuario/{id}/acesso.
            Definir-Funcao $adminJarMatriz $trabalhadorId "DIRIGENTE"
            Definir-Perfil $adminJarMatriz $trabalhadorId $loginTrab "ASSISTIDO"

            Login-Como $loginTrab $senhaTrab | Out-Null
            $r = Invoke-CurlForm -Url "$BaseUrl/"
            Check "Função de Dirigente com perfil ASSISTIDO não libera a listagem (403)" ($r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores"
            Check "Função de Dirigente com perfil ASSISTIDO não libera 'Cadastrar Trabalhador' (403)" (
                $r.StatusCode -eq 403)
            $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/$trabalhadorId/cartao"
            Check "Com perfil ASSISTIDO o trabalhador ainda vê o próprio cartão (200)" ($r.StatusCode -eq 200)

            # 2026-10-03: salvar as funções na tela do trabalhador ajusta o perfil sozinho, sem seletor.
            $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores/$trabalhadorId"
            Check "Tela do trabalhador não tem mais o seletor de perfil" ($r.Body -notmatch 'name="perfilAcesso"')
            Definir-Funcao $adminJarMatriz $trabalhadorId "DIRIGENTE"
            Check "Salvar funções promove o perfil a TRABALHADOR e avisa" (
                $script:UltimaPaginaTrabalhadores -match "Perfil do acesso alterado para TRABALHADOR")
            Login-Como $loginTrab $senhaTrab | Out-Null
            $r = Invoke-CurlForm -Url "$BaseUrl/trabalhadores"
            Check "Perfil promovido automaticamente libera o módulo (200)" ($r.StatusCode -eq 200)

            # Desmarcar todas as funções devolve o perfil a ASSISTIDO.
            Definir-Funcao $adminJarMatriz $trabalhadorId ""
            $perfilDespromovido = Invoke-SqlScalar "SELECT perfil_acesso FROM assistido WHERE id = $trabalhadorId;"
            Check "Sem nenhuma função, o perfil do acesso volta a ASSISTIDO" ($perfilDespromovido -eq "ASSISTIDO")
        } finally {
            if (Test-Path $script:CookieJar) { Remove-Item $script:CookieJar -Force }
            $script:CookieJar = $adminJarMatriz
            $script:CsrfToken = $adminCsrfMatriz
        }

        # Armadilha que a matriz cria: login de perfil TRABALHADOR sem nenhuma função entra e não
        # enxerga nada além do próprio cartão. O formulário de acesso avisa em vez de bloquear.
        Invoke-Sql "DELETE FROM trabalhador_funcao WHERE trabalhador_id IN (SELECT id FROM trabalhador WHERE assistido_id = $trabalhadorId);" | Out-Null
        $r = Salvar-Acesso $trabalhadorId @{ login = $loginTrab; perfil = "TRABALHADOR"; acessoAtivo = "true" }
        $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/$trabalhadorId"
        Check "Acesso de trabalhador sem função avisa que ele só verá o próprio cartão" (
            $r.Body -match "não tem nenhuma função de trabalho")

        # Devolve o Passista do passo anterior, estado em que a seção 16 encontra este trabalhador.
        Definir-Funcao $script:CookieJar $trabalhadorId "PASSISTA"
    }

    # 15b. Item 3 dos perfis: ninguém conduz o próprio tratamento — o admin (que também é um
    # assistido, id do login) não registra a própria avaliação/entrevista.
    $meuId = Invoke-SqlScalar "SELECT id FROM assistido WHERE login = '$AdminLogin';"
    if ($meuId) {
        $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/$meuId/nova-avaliacao"
        Check "Trabalhador não abre a própria avaliação (volta ao prontuário)" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$meuId$")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/prontuario/$meuId/avaliacao" -Form @{ data = "07/01/2024"; evolucao = "BOM" }
        Check "Trabalhador não registra a própria avaliação" ($r.StatusCode -eq 302 -and $r.Location -match "prontuario/$meuId$")

        $avaliacoesProprias = Invoke-SqlScalar "SELECT count(*) FROM avaliacao WHERE assistido_id = $meuId;"
        Check "Nenhuma avaliação própria foi gravada" ($avaliacoesProprias -eq "0")

        $r = Invoke-CurlForm -Url "$BaseUrl/prontuario/$meuId"
        Check "Prontuário avisa que outro trabalhador precisa fazer o atendimento" ($r.Body -match "Ninguém conduz o próprio tratamento")
    }

    # 16. Check-in por QR code (CLAUDE.md 3.12) e Módulo Sessão (3.13): o QR fica no cartão do
    # assistido e é a recepção que escaneia, e só carimba enquanto a janela de check-in da sessão de
    # hoje (sessao_assistencia) estiver aberta. A sessão e a preleção de hoje são inseridas por SQL
    # porque hoje pode não ser Domingo/Terça (a tela, com razão, só aceita esses dias) — e a janela
    # de check-in exige a data de hoje.
    $trabalhadorRowId = Invoke-SqlScalar "SELECT id FROM trabalhador ORDER BY id LIMIT 1;"
    Check "Existe um trabalhador para ser preletor da sessão de hoje" ([bool]$trabalhadorRowId)

    if ($trabalhadorRowId) {
        $prelecaoHojeId = Invoke-SqlScalar "SELECT id FROM prelecao WHERE data_apresentacao = CURRENT_DATE LIMIT 1;"
        if (-not $prelecaoHojeId) {
            $prelecaoHojeId = Invoke-SqlScalar "INSERT INTO prelecao (data_apresentacao, tema, trabalhador_id) VALUES (CURRENT_DATE, '$temaPrelecaoTeste', $trabalhadorRowId) RETURNING id;"
        }
        # A sessão de hoje pode já existir no banco de dev; só é apagada na limpeza se o teste a criou.
        $sessaoHojeId = Invoke-SqlScalar "SELECT id FROM sessao_assistencia WHERE data = CURRENT_DATE;"
        if (-not $sessaoHojeId) {
            $sessaoHojeId = Invoke-SqlScalar "INSERT INTO sessao_assistencia (data, dia_frequencia) VALUES (CURRENT_DATE, 'DOMINGO_08H') RETURNING id;"
            $script:sessaoHojeCriada = $sessaoHojeId
        }
        # Sessão em data passada: serve para provar que a janela só abre no dia da sessão.
        $sessaoAntigaId = Invoke-SqlScalar "INSERT INTO sessao_assistencia (data, dia_frequencia) VALUES (DATE '2024-01-07', 'DOMINGO_08H') ON CONFLICT (data) DO UPDATE SET data = EXCLUDED.data RETURNING id;"
        $script:sessaoAntigaId = $sessaoAntigaId

        $codigoCartao = Invoke-SqlScalar "SELECT codigo_cartao FROM assistido WHERE id = $assistidoId;"
        Check "Cartão do assistido tem código para o QR" ([bool]$codigoCartao)

        $r = Invoke-CurlForm -Url "$cartaoUrl/qrcode.png"
        Check "QR code do cartão responde 200 como imagem PNG" ($r.StatusCode -eq 200 -and $r.Body -match "image/png")

        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/$codigoCartao"
        Check "Scan sem check-in aberto avisa que falta abrir a sessão" ($r.StatusCode -eq 200 -and $r.Body -match "Nenhuma sessão está com o check-in aberto")

        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/qr-que-nao-existe"
        Check "QR desconhecido não resolve nenhum cartão" ($r.Body -match "não corresponde a nenhum cartão")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/sessao" -Form @{ data = "08/01/2024" }
        Check "Segunda-feira não vira sessão (volta para a lista)" ($r.StatusCode -eq 302 -and $r.Location -match "sessao$")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/sessao/$sessaoAntigaId/checkin/abrir"
        $abertoAntiga = Invoke-SqlScalar "SELECT COALESCE(checkin_aberto_em::text, 'NULL') FROM sessao_assistencia WHERE id = $sessaoAntigaId;"
        Check "Check-in não abre em sessão de outra data" ($abertoAntiga -eq "NULL")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/sessao/$sessaoHojeId/checkin/abrir"
        Check "Abrir check-in da sessão de hoje volta para o painel da sessão" ($r.StatusCode -eq 302 -and $r.Location -match "sessao/$sessaoHojeId$")
        $abertoHoje = Invoke-SqlScalar "SELECT COALESCE(checkin_aberto_em::text, 'NULL') FROM sessao_assistencia WHERE id = $sessaoHojeId;"
        Check "Janela de check-in registrada como aberta" ($abertoHoje -ne "NULL")

        $r = Invoke-CurlForm -Url "$BaseUrl/sessao/$sessaoHojeId"
        Check "Painel da sessão abre com indicadores e escala" ($r.StatusCode -eq 200 -and $r.Body -match "Indicadores" -and $r.Body -match "Câmara de Passe")

        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/$codigoCartao"
        Check "Scan com check-in aberto mostra o assistido e o botão de carimbar" ($r.Body -match [regex]::Escape($nomeEditado) -and $r.Body -match "Carimbar presença")

        # Sem dia de assistência definido, qualquer data é aceita — assim o teste não depende do dia
        # da semana em que roda (com dia definido, a validação do TratamentoService barraria).
        Invoke-Sql "UPDATE assistido SET dia_frequencia = NULL WHERE id = $assistidoId;" | Out-Null

        # A última presença é de 2024: o cartão expira (21 dias) e o QR só carimba após a recepção
        # confirmar o reinício em P2 (/reiniciar).
        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/checkin/$codigoCartao"
        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/$codigoCartao"  # consome o flash de erro
        $statusQr = Invoke-SqlScalar "SELECT status_cartao FROM assistido WHERE id = $assistidoId;"
        Check "QR de cartão expirado não carimba e marca 'Incompleto por Tempo'" ($statusQr -eq "INCOMPLETO_POR_TEMPO")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/checkin/$codigoCartao/reiniciar"
        Check "Carimbar presença pelo QR redireciona de volta para o check-in" ($r.StatusCode -eq 302 -and $r.Location -match "checkin/$codigoCartao")

        $presencaHoje = Invoke-SqlScalar "SELECT ouvinte::text || '|' || COALESCE(prelecao_id::text, 'NULL') FROM sessao_tratamento WHERE assistido_id = $assistidoId AND data_consulta = CURRENT_DATE ORDER BY id LIMIT 1;"
        Check "Presença do QR gravada como efetiva e ligada à preleção da sessão" ($presencaHoje -eq "false|$prelecaoHojeId")

        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/$codigoCartao"
        Check "Check-in confirma a presença carimbada" ($r.Body -match "presença carimbada no cartão")

        $r = Invoke-CurlForm -Url "$BaseUrl/sessao/$sessaoHojeId/indicadores"
        Check "Indicadores da sessão contam a presença carimbada" ($r.StatusCode -eq 200 -and $r.Body -match "painelIndicadores" -and $r.Body -match [regex]::Escape($nomeEditado))

        # 2ª leitura na mesma semana: a regra semanal transforma em ouvinte, sem avançar o cartão.
        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/checkin/$codigoCartao"
        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/$codigoCartao"
        Check "2ª leitura na mesma semana entra como ouvinte" ($r.Body -match "já tinha presença nesta semana")

        # Ouvinte por decisão da recepção (quem aparece num dia que não é o dele).
        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/checkin/$codigoCartao/ouvinte"
        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/$codigoCartao"
        Check "Recepção consegue marcar ouvinte explicitamente" ($r.Body -match "entrou como ouvinte")

        $ouvintesHoje = Invoke-SqlScalar "SELECT count(*) FROM sessao_tratamento WHERE assistido_id = $assistidoId AND data_consulta = CURRENT_DATE AND ouvinte = true;"
        Check "As duas presenças extras do dia ficaram como ouvinte" ($ouvintesHoje -eq "2")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/sessao/$sessaoHojeId/checkin/fechar"
        $fechadoHoje = Invoke-SqlScalar "SELECT COALESCE(checkin_fechado_em::text, 'NULL') FROM sessao_assistencia WHERE id = $sessaoHojeId;"
        Check "Encerrar check-in registra o fechamento da janela" ($fechadoHoje -ne "NULL")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/checkin/$codigoCartao"
        $r = Invoke-CurlForm -Url "$BaseUrl/checkin/$codigoCartao"
        Check "Com o check-in fechado o QR não carimba mais presença" ($r.Body -match "Nenhuma sessão está com o check-in aberto")
    }

    # 16b. Cancelar / excluir a sessão (2026-10-03). Usa um domingo de 2099, vazio de propósito:
    # cancelar desfaz as presenças de TODO MUNDO na data, e uma data real do banco de dev levaria
    # junto presenças que não são do teste. A presença é de ouvinte, inserida por SQL, para conferir
    # que ela some sem mexer no cartão de ninguém.
    $dataCancelar = "04/01/2099"
    $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/sessao" -Form @{ data = $dataCancelar }
    $sessaoCancelarId = Invoke-SqlScalar "SELECT id FROM sessao_assistencia WHERE data = DATE '2099-01-04';"
    $script:sessaoCancelarId = $sessaoCancelarId
    Check "Sessão de 2099 criada para testar o cancelamento" ([bool]$sessaoCancelarId)
    if ($sessaoCancelarId) {
        Invoke-Sql "INSERT INTO sessao_tratamento (assistido_id, data_consulta, ouvinte, visto, assistencia, evangelho_no_lar, leituras, escola, trabalho_espiritual, medico) VALUES ($assistidoId, DATE '2099-01-04', true, false, false, false, false, false, false, false);" | Out-Null

        $r = Invoke-CurlForm -Url "$BaseUrl/sessao/$sessaoCancelarId"
        Check "Painel oferece cancelar e excluir, avisando das presenças" (
            $r.Body -match "Cancelar sessão" -and $r.Body -match "Excluir sessão" -and $r.Body -match "presença\(s\)")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/sessao/$sessaoCancelarId/cancelar" -Form @{ motivo = "" }
        $r = Invoke-CurlForm -Url "$BaseUrl/sessao/$sessaoCancelarId"
        $canceladaSemMotivo = Invoke-SqlScalar "SELECT COALESCE(cancelada_em::text, 'NULL') FROM sessao_assistencia WHERE id = $sessaoCancelarId;"
        Check "Cancelar sem motivo é recusado" ($canceladaSemMotivo -eq "NULL" -and $r.Body -match "Informe o motivo")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/sessao/$sessaoCancelarId/cancelar" -Form @{ motivo = "Feriado SMOKE" }
        $r = Invoke-CurlForm -Url "$BaseUrl/sessao/$sessaoCancelarId"
        $motivo = Invoke-SqlScalar "SELECT COALESCE(motivo_cancelamento, 'NULL') FROM sessao_assistencia WHERE id = $sessaoCancelarId;"
        Check "Cancelar grava o motivo" ($motivo -eq "Feriado SMOKE")
        $presencasCanceladas = Invoke-SqlScalar "SELECT count(*) FROM sessao_tratamento WHERE data_consulta = DATE '2099-01-04';"
        Check "Cancelar desfaz as presenças da data" ($presencasCanceladas -eq "0")
        Check "Painel mostra a sessão cancelada, o motivo e quantas presenças saíram" (
            $r.Body -match "Sessão cancelada" -and $r.Body -match "Feriado SMOKE" -and $r.Body -match "Presenças desfeitas: 1 pessoa")

        $r = Invoke-CurlForm -Url "$BaseUrl/sessao"
        Check "Lista de sessões marca a sessão como cancelada" ($r.Body -match "Cancelada")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/sessao/$sessaoCancelarId/reativar"
        $r = Invoke-CurlForm -Url "$BaseUrl/sessao/$sessaoCancelarId"
        $canceladaDepois = Invoke-SqlScalar "SELECT COALESCE(cancelada_em::text, 'NULL') FROM sessao_assistencia WHERE id = $sessaoCancelarId;"
        Check "Reativar desfaz o cancelamento" ($canceladaDepois -eq "NULL" -and $r.Body -match "Sessão reativada")

        $r = Invoke-CurlForm -Method POST -Url "$BaseUrl/sessao/$sessaoCancelarId/excluir"
        Check "Excluir volta para a lista de sessões" ($r.StatusCode -eq 302 -and $r.Location -match "sessao$")
        $r = Invoke-CurlForm -Url "$BaseUrl/sessao"  # consome o flash
        $restantes = Invoke-SqlScalar "SELECT count(*) FROM sessao_assistencia WHERE id = $sessaoCancelarId;"
        Check "Excluir apaga a sessão" ($restantes -eq "0")
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
    # Check-in (seção 16): as preleções do teste só podem sair depois das sessões que as referenciam;
    # e se o teste reaproveitou uma preleção que já existia para hoje, a janela dela volta ao estado
    # original (fechada) para não deixar um check-in aberto no banco de dev.
    Invoke-Sql "DELETE FROM prelecao WHERE tema IN ('$temaPrelecaoTeste', '$temaPrelecaoAntiga');" | Out-Null
    if ($script:sessaoAntigaId) { Invoke-Sql "DELETE FROM sessao_assistencia WHERE id = $($script:sessaoAntigaId);" | Out-Null }
    if ($script:sessaoCancelarId) { Invoke-Sql "DELETE FROM sessao_assistencia WHERE id = $($script:sessaoCancelarId);" | Out-Null }
    if ($script:sessaoHojeCriada) {
        Invoke-Sql "DELETE FROM sessao_assistencia WHERE id = $($script:sessaoHojeCriada);" | Out-Null
    } else {
        Invoke-Sql "UPDATE sessao_assistencia SET checkin_aberto_em = NULL, checkin_fechado_em = NULL WHERE data = CURRENT_DATE;" | Out-Null
    }
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
