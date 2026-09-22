# Especificação do Sistema: N.A.E. Divina Luz
**Projeto:** Automatização do Prontuário de Assistência Espiritual
**Stack Tecnológico:** Java 17, Spring Boot 4.1.1, PostgreSQL, Flyway, Thymeleaf, HTML5/Bootstrap 5.3.

## 1. Visão Geral
Sistema WEB para substituição do prontuário físico (fichas azuis) por um ambiente digital. Controla o cadastro dos assistidos (incluindo os que também são trabalhadores da casa), o histórico de sessões semanais, o ciclo de avaliações/entrevistas espirituais (regra de 4 sessões) e o dia de assistência (terça 19h ou domingo 8h) de cada um.

## 2. Regras de Negócio (Camada de Serviço — `TratamentoService`)

- **Frequência de Sessões (regra dos 7 dias):** o assistido só pode contabilizar uma sessão a cada 7 dias. Uma nova sessão com menos de 7 dias desde a última é bloqueada (`RegraNegocioException`).

- **Consistência de Dia da Semana:** se o assistido já tem um `diaFrequencia` definido (terça 19h ou domingo 8h), a data de qualquer **Sessão**, **Entrevista** ou "1ª sessão de um tratamento novo" precisa cair nesse dia da semana — do contrário é bloqueada. Se o assistido ainda não tem dia definido, nenhuma data é exigida. A data da **Avaliação** é livre e nunca é validada contra esse dia (ver distinção Avaliação x Entrevista abaixo).

- **Assistência ≠ Sessão, e o ciclo de 4 sessões:** "Sessão" é o evento que ocorre toda terça e domingo, no qual o assistido marca presença (um `SessaoTratamento` por presença). A cada bloco de 4 sessões computadas no ciclo atual, o sistema **bloqueia novas sessões** até que o assistido passe por:
  1. **Avaliação** — diagnóstico espiritual (histórico, evolução, observações do médium). Data livre, não amarrada ao dia de assistência.
  2. **Entrevista** — vinculada 1:1 a uma Avaliação específica. É nela que o entrevistador comunica/decide o **tratamento das próximas 4 sessões** (`tratamentoIndicado`), e sua data **precisa cair no dia de assistência do assistido**.
  Só a Entrevista completa (não a Avaliação isolada) libera a sessão seguinte e atualiza `Assistido.tratamentoAtual`.

- **Reinício automático por ausência (regra dos 21 dias):** se o intervalo entre duas sessões passa de 21 dias (faltou na 3ª semana), o tratamento é reiniciado em P2 **independentemente do tratamento anterior**, e o ciclo (contagem de sessões/avaliações/entrevistas) zera a partir dessa nova sessão (`Assistido.cicloIniciadoEm`). Uma nova entrevista é sugerida (opcional, via flash message), mas não é bloqueante.

- **Definir/alterar tratamento exige a data da 1ª sessão:** sempre que `Assistido.tratamentoAtual` é atribuído a um valor **novo** (diferente do atual) pelo cadastro, pela edição de dados ou pelo card "Alterar Tratamento" do prontuário, a data da 1ª sessão desse novo ciclo é obrigatória — e essa sessão (`numeroSerie = 1`) já é criada automaticamente com essa data, resetando `cicloIniciadoEm`. Se o tratamento não muda (resubmissão do mesmo valor) ou está sendo limpo (`null`), nenhuma data é exigida. Essa regra **não** se aplica à indicação de tratamento feita pela Entrevista, pois ali o ciclo já está em andamento (ver `TratamentoService.definirTratamento`).

- **Exclusão lógica do prontuário:** um assistido nunca é removido do banco — apenas desativado (`Assistido.ativo = false`). Ele some da listagem principal por padrão (filtro `mostrarInativos` reexibe todos) e pode ser reativado a qualquer momento, preservando todo o histórico.

- **Perfil de Trabalhador:** todo trabalhador é um assistido (passa pelas mesmas sessões de assistência), mas nem todo assistido é trabalhador. Quando `Vinculo = TRABALHADOR`, o assistido pode ter uma ou mais funções da casa espiritual (`TipoTrabalhador`, catálogo fechado — ver 3.6), definíveis já no cadastro ou depois, numa tela dedicada.

- **Rastreabilidade do Dia de Assistência:** qualquer alteração no `diaFrequencia` do assistido (inclusive a primeira definição) é registrada em `HistoricoDiaFrequencia` (valor anterior, valor novo, data/hora, motivo opcional).

## 3. Modelagem de Dados (Entidades)

### 3.1. Assistido (Dados Cadastrais)
- **Atributos:** ID (PK), Nome, Residência, Data de Nascimento (Idade é `@Transient`, calculada como `Period.between(dataNascimento, hoje)`), Estado Civil, Sexo (M/F/I — Indefinido), E-mail, Vínculo (String livre: ASSISTIDO, TRABALHADOR, ALUNO), Dia de Assistência (Enum `DiaFrequencia`, opcional), Ciclo Iniciado Em (data-base para contar sessões/avaliações/entrevistas do ciclo atual), Ativo (boolean, default `true` — exclusão lógica).
- **Relacionamentos:** N:1 com `TipoTratamento` (`tratamentoAtual`), 1:N com `Avaliacao`, `SessaoTratamento` e `HistoricoDiaFrequencia`, 1:1 com `Trabalhador` (opcional).

### 3.2. DiaFrequencia (enum)
- `TERCA_19H` (terça-feira, 19h) e `DOMINGO_08H` (domingo, 08h) — os dois horários fixos de assistência espiritual da casa. Cada valor carrega `DayOfWeek`, `LocalTime` e um `label` de exibição.

### 3.3. Avaliacao (diagnóstico espiritual)
- **Atributos:** ID (PK), Assistido (FK), Numero da Vez (1 a 8, contado dentro do ciclo atual), Data (livre), Histórico (texto longo), Observações (texto longo — recomendações do médium ao assistido), Evolução (Enum: BOM, INDIFERENTE, PIOR, MELHOR).
- **Relacionamento:** 1:1 com `Entrevista` (inverso — `mappedBy`), pode não ter entrevista ainda.

### 3.4. Entrevista (comunica o tratamento decidido)
- **Atributos:** ID (PK), Avaliação (FK, 1:1, `UNIQUE` — uma entrevista por avaliação), Assistido (FK direto, para consultas), Data (**precisa cair no dia de assistência do assistido**), Entrevistador, Tratamento Indicado (FK `TipoTratamento`, pode repetir o anterior).
- Ao ser salva, atualiza `Assistido.tratamentoAtual` (se `tratamentoIndicado` informado) e conta para liberar a regra das 4 sessões.

### 3.5. SessaoTratamento (Verso do Cartão)
- **Atributos:** ID (PK), Assistido (FK), Numero da Série (1 a 8, contado dentro do ciclo atual), Data da Consulta, Observações.
- **Recomendações (Booleanos):** Visto, Assistência, Evangelho no Lar, Leituras, Escola, Trabalho Espiritual, Médico. (Atenção: "Assistência" aqui é só uma dessas recomendações — não confundir com "assistência espiritual"/o evento de comparecer à casa, que é a Sessão em si.)

### 3.6. TipoTratamento (Catálogo de Tratamentos Espirituais)
- **Atributos:** ID (PK), Código, Nome, Como Funciona (texto longo), Objetivo/Indicação (texto longo), Triagem/Observações (texto longo).
- **Catálogo (seed via migration V3):** P1, P2, P3A (ou P3F), P3C, P3E, P3DC/P3V, A2, CH, P4. P2 é o tratamento padrão de entrada (porta de entrada para quem ainda não tem tratamento definido na 1ª sessão, e também o tratamento do reinício automático por ausência).

### 3.7. Trabalhador (Perfil de Trabalhador)
- **Atributos:** ID (PK), Assistido (FK, 1:1, `UNIQUE`), Funções (`Set<TipoTrabalhador>`, `@ElementCollection` — um trabalhador pode acumular mais de uma função).
- **TipoTrabalhador (enum):** `DIRIGENTE`, `EXPOSITOR_PRELETOR`, `PASSISTA`, `FACILITADOR`, `EDUCADOR_EVANGELIZACAO` — cada um com `label` e `descricao` de exibição.

### 3.8. HistoricoDiaFrequencia (log de rastreabilidade)
- **Atributos:** ID (PK), Assistido (FK), Dia Anterior, Dia Novo (ambos `DiaFrequencia`, nuláveis), Data/Hora, Motivo (texto livre, opcional).

## 4. Estrutura do Projeto

- **`application.properties`**: PostgreSQL na porta 5432 (`divinaluz_db`), aplicação na porta 8081 com contexto `/divinaluz`. Spring Security removido temporariamente. Schema gerenciado por Flyway (`ddl-auto=validate`, `baseline-on-migrate=true`).
- **Pacote `model`**: `Assistido`, `Avaliacao`, `Entrevista`, `SessaoTratamento`, `TipoTratamento`, `Trabalhador`, `DiaFrequencia` (enum), `TipoTrabalhador` (enum), `Evolucao` (enum), `HistoricoDiaFrequencia`.
- **Pacote `repository`**: um `JpaRepository` por entidade (`AssistidoRepository`, `AvaliacaoRepository`, `EntrevistaRepository`, `SessaoRepository`, `TipoTratamentoRepository`, `TrabalhadorRepository`, `HistoricoDiaFrequenciaRepository`), cada um com os `findBy...`/`countBy...` necessários às regras de negócio (ex.: `countByAssistidoIdAndDataConsultaGreaterThanEqual` para contar sessões do ciclo atual).
- **Pacote `service`**: `TratamentoService` concentra todas as regras da seção 2 (`registrarSessao`, `registrarAvaliacao`, `registrarEntrevista`, `definirTratamento`, `validarDiaDaSemana` privado).
- **Pacote `config`**: `TipoTratamentoConverter` (Spring `Converter<String, TipoTratamento>`) para os `<select>` do Thymeleaf fazerem bind direto na entidade pelo ID. Enums (`DiaFrequencia`, `TipoTrabalhador`, `Evolucao`) não precisam de converter próprio — Spring já converte `String -> Enum` nativamente.
- **Pacote `exception`**: `RegraNegocioException` (violação genérica) e `AvaliacaoPendenteException` (subclasse específica para o bloqueio das 4 sessões).
- **Pacote `controller`**: `ProntuarioController` com as rotas:
  - `GET /` → lista assistidos (só ativos por padrão; `?mostrarInativos=true` mostra todos), com checklist de frequência do mês corrente.
  - `GET /novo` / `POST /salvar` → cadastro (inclui tratamento inicial opcional + funções de trabalhador, se `Vinculo = TRABALHADOR`).
  - `GET /prontuario/{id}` → detalhes, resumo rápido, histórico de sessões/avaliações (abas).
  - `GET /prontuario/{id}/editar` / `POST /prontuario/{assistidoId}/editar` → edição dos dados cadastrais.
  - `POST /prontuario/{assistidoId}/desativar` / `POST /prontuario/{assistidoId}/reativar` → exclusão lógica.
  - `POST /prontuario/{id}/tratamento` → "Alterar Tratamento" (exige data da 1ª sessão quando muda).
  - `POST /prontuario/{assistidoId}/dia-frequencia` → define/altera o dia de assistência (grava histórico).
  - `GET /prontuario/{id}/trabalhador` / `POST /prontuario/{assistidoId}/trabalhador` → perfil de trabalhador.
  - `GET /prontuario/{id}/nova-sessao` / `POST /prontuario/{assistidoId}/sessao` → registro de sessão.
  - `GET /prontuario/{id}/nova-avaliacao` / `POST /prontuario/{assistidoId}/avaliacao` → registro de avaliação (diagnóstico).
  - `GET /prontuario/{id}/nova-entrevista?avaliacaoId=X` / `POST /prontuario/{assistidoId}/entrevista?avaliacaoId=X` → registro da entrevista referente a uma avaliação específica.
- **Migrations Flyway**: V1 (tabelas iniciais) · V2 (`avaliacao`) · V3 (`tipo_tratamento` + seed) · V4 (`assistido.tratamento_atual_id`) · V5 (`avaliacao.tratamento_indicado_id`, hoje sem uso — ver 6) · V6 (`assistido.ciclo_iniciado_em`) · V7 (`trabalhador` + `trabalhador_funcao`) · V8 (`assistido.data_nascimento`) · V9 (`assistido.dia_frequencia`) · V10 (`historico_dia_frequencia`) · V11 (`assistido.ativo`) · V12 (`avaliacao.observacoes`) · V13 (`entrevista`).

## 5. Front-end (Thymeleaf/Templates)
- **`fragments/layout.html`**: `pageHead(title)` (⚠️ não usar o nome `head` — colide com a tag `<head>`) e `navbar`. CSS próprio em `static/css/app.css` (paleta teal/turquesa inspirada no cartão físico original).
- **`index.html`**: listagem com busca por nome, filtro de inativos, colunas de Vínculo/Dia/Tratamento e o checklist de **Frequência (Mês)** — mostra a própria data (`dd/MM`) de cada dia esperado no mês, verde se houve sessão e cinza se faltou.
- **`form.html`**: cadastro/edição (mesmo template, `modoEdicao` liga/desliga textos e rotas). Mostra a seção de Funções de Trabalhador via JS quando `Vinculo = TRABALHADOR` é selecionado, e o campo "Data da 1ª Sessão" quando um tratamento novo é escolhido.
- **`prontuario.html`**: resumo rápido no topo (vínculo, status, tratamento, dia de assistência, idade, próxima entrevista prevista + ações principais), cards de Dados/Tratamento/Dia de Assistência/Perfil de Trabalhador em grade, e abas (Sessões / Avaliações) para o histórico.
- **`sessao-form.html`**: data + 7 checkboxes de recomendações + observações.
- **`avaliacao-form.html`**: data (livre), evolução, histórico, observações.
- **`entrevista-form.html`**: mostra a qual avaliação se refere, data (com aviso do dia de assistência esperado), entrevistador, tratamento indicado.
- **`trabalhador-form.html`**: checklist de funções (`TipoTrabalhador`) com descrição de cada uma.

## 6. Cuidados / Armadilhas Já Encontradas
- **Path variable `id` + `@ModelAttribute` com campo `id`**: o `ExtendedServletRequestDataBinder` do Spring MVC injeta a variável de path automaticamente em qualquer propriedade do command object com o mesmo nome. Regra: nunca nomear um `@PathVariable` como `id` na mesma rota que recebe via `@ModelAttribute` uma entidade que tenha um campo `id` (usar `assistidoId`, por exemplo).
- **Flyway baseline**: o banco de dev foi originalmente criado via `ddl-auto=update` e depois baselineado no Flyway (V1) sem que o schema real batesse 100% com o script. Ao adicionar uma entidade/coluna nova, sempre conferir contra o banco real (`\dt`, `\d <tabela>`) antes de assumir que a migration já rodou.
- **FK sem CASCADE de fato**: por causa do mesmo desalinhamento do baseline, a FK de `sessao_tratamento` para `assistido` no banco real **não** tem `ON DELETE CASCADE` (embora a migration V1 diga isso). Excluir um assistido via SQL direto exige apagar antes as linhas de `sessao_tratamento` (e `avaliacao`) manualmente. Os scripts de smoke-test já fazem essa limpeza explícita.
- **Colunas legadas mantidas sem uso**: ao trocar de modelo (ex.: `idade` → `dataNascimento` calculado; `avaliacao.entrevistador`/`tratamento_indicado_id` → movidos para `Entrevista`), a coluna antiga é deixada no banco (não mapeada pela entidade) em vez de um `DROP COLUMN`, para não perder dados já gravados. Isso é intencional — não é preciso "arrumar" essas colunas órfãs.
- **Fragmento Thymeleaf não pode se chamar `head`**: `th:fragment="head(title)"` colide com a tag literal `<head>` do HTML e quebra a resolução do fragmento (`TemplateProcessingException`). Por isso o fragmento se chama `pageHead`.
- **Flash attributes + `curl` sem cookie jar**: qualquer rota que use `RedirectAttributes.addFlashAttribute(...)` cria uma `HttpSession` no servidor; como o `curl.exe` dos scripts de smoke-test não guarda cookies entre chamadas, o Tomcat anexa `;jsessionid=...` na `Location` do redirect — o que quebra checagens de regex com `$` no final da URL, e faz o conteúdo da flash message não aparecer na página seguinte (sessão não é recuperada sem o cookie/jsessionid). Nesses casos, os testes verificam o **efeito** da regra (ex.: "tratamento não mudou") em vez do texto da mensagem de erro.
- **`@OneToOne(mappedBy = ...)` e Thymeleaf**: `Avaliacao.entrevista` é o lado inverso do relacionamento com `Entrevista` e pode ser acessado diretamente nos templates (`${av.entrevista}`) porque o fetch padrão de `@OneToOne` é `EAGER` e o Spring Boot mantém a sessão do Hibernate aberta durante a renderização da view (`spring.jpa.open-in-view`, ligado por padrão).

## 7. Scripts de Automação (`scripts/`)
- **`dev-run.ps1`** / **`dev-stop.ps1`**: sobem/derrubam a aplicação (matam processo na porta 8081 se necessário).
- **`smoke-test.ps1`**: bateria de ~45 verificações end-to-end via `curl`, cobrindo todas as regras da seção 2 (cria e limpa seus próprios dados, prefixo `SMOKE_TEST_`).
- **`dev-test.ps1`**: orquestra os três (sobe → testa → derruba, mesmo se o teste falhar).
- **`hook-compile-on-java-edit.ps1`**: hook do Claude Code (`PostToolUse`) que roda `mvnw compile -q` a cada edição de `.java`.

## 8. Próximos Passos
1. Reativar o Spring Security (removido temporariamente para facilitar os testes) antes de qualquer uso em produção.
2. Cobrir as regras de negócio com testes unitários JUnit em `TratamentoService` (hoje a cobertura é só end-to-end via `smoke-test.ps1`).
3. Avaliar se vale a pena um `DROP COLUMN` das colunas legadas órfãs (`assistido.idade`, `avaliacao.entrevistador`, `avaliacao.tratamento_indicado_id`) depois que não houver mais dúvida sobre a migração dos dados antigos.
4. Criar módulo de Entrevista.
5. Criar módulo de Avaliação.
6. Criar módulo de Assistido.
