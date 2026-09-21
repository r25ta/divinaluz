# Especificação do Sistema: N.A.E. Divina Luz
**Projeto:** Automatização do Prontuário de Assistência Espiritual
**Stack Tecnológico:** Java 17+, Spring Boot 3.x, PostgreSQL, Thymeleaf, HTML5/Bootstrap 5.

## 1. Visão Geral
Sistema WEB para substituição do prontuário físico (fichas azuis) por um ambiente digital. O sistema controlará os dados de cadastro dos assistidos, o histórico de sessões semanais e o ciclo de avaliações espirituais (regra de 4 sessões).

## 2. Regras de Negócio (Camada de Serviço)
- **Frequência de Sessões (Validação Semanal):** O assistido só pode contabilizar uma sessão a cada 7 dias. O sistema deve barrar ou alertar o registro de uma nova sessão caso a última tenha ocorrido em um intervalo menor.
- **Gatilho de Avaliação (Regra das 4 Sessões):** A cada bloco de 4 sessões computadas, o assistido precisa passar por uma nova "Avaliação". O sistema deve solicitar/bloquear novas sessões até que os dados dessa reavaliação sejam inseridos.

## 3. Modelagem de Dados (Entidades)

### 3.1. Assistido (Dados Cadastrais)
- **Atributos:** ID (PK), Nome, Residência, Idade, Estado Civil, Sexo, E-mail, Vínculo (Enum: ASSISTIDO, TRABALHADOR, ALUNO).
- **Relacionamentos:** 1:N com `Avaliacao`, 1:N com `SessaoTratamento`.

### 3.2. Avaliacao (Frente do Cartão)
- **Atributos:** ID (PK), Assistido (FK), Numero da Vez (1 a 8), Data, Entrevistador, Histórico (Texto longo), Evolução (Enum: BOM, INDIFERENTE, PIOR, MELHOR).

### 3.3. SessaoTratamento (Verso do Cartão)
- **Atributos:** ID (PK), Assistido (FK), Numero da Serie (1 a 8), Data da Consulta, Observações.
- **Recomendações (Booleanos):** Visto, Assistência, Evangelho no Lar, Leituras, Escola, Trabalho Espiritual, Médico.

### 3.4. TipoTratamento (Catálogo de Tratamentos Espirituais)
- **Atributos:** ID (PK), Código, Nome, Como Funciona (texto longo), Objetivo/Indicação (texto longo), Triagem/Observações (texto longo).
- **Relacionamento:** `Assistido.tratamentoAtual` (N:1) — cada assistido tem um tratamento atual, atribuído/alterado após a triagem.
- **Catálogo (seed via migration V3):** P1, P2, P3A (ou P3F), P3C, P3E, P3DC/P3V, A2, CH, P4.

## 4. Estrutura do Projeto Implementada até o Momento
- **`application.properties`**: Configurado para PostgreSQL na porta 5432, aplicação rodando na porta 8081 com contexto `/divinaluz`. O Spring Security foi removido temporariamente para facilitar os testes. Schema gerenciado por Flyway (`ddl-auto=validate`).
- **Pacote `model`**: `Assistido`, `SessaoTratamento`, `Avaliacao`, `Evolucao` (enum) e `TipoTratamento` mapeados com JPA (`@Entity`).
- **Pacote `repository`**: `AssistidoRepository`, `SessaoRepository`, `AvaliacaoRepository`, `TipoTratamentoRepository`.
- **Pacote `service`**: `TratamentoService` com as regras de negócio implementadas: bloqueio de sessão com menos de 7 dias e exigência de nova `Avaliacao` a cada bloco de 4 sessões (lança `RegraNegocioException`).
- **Pacote `config`**: `TipoTratamentoConverter` (Spring `Converter<String, TipoTratamento>`) para permitir que os `<select>` do Thymeleaf façam bind direto na entidade pelo ID.
- **Pacote `controller`**: `ProntuarioController` configurado com rotas:
  - `GET /` -> Lista todos os assistidos (com tratamento atual).
  - `GET /novo` / `POST /salvar` -> Cadastro de assistido, incluindo seleção de tratamento atual.
  - `GET /prontuario/{id}` -> Exibe os detalhes, histórico de sessões e tratamento atual do assistido.
  - `GET /prontuario/{id}/nova-sessao` / `POST /prontuario/{assistidoId}/sessao` -> Registro de nova sessão, com erro de regra de negócio voltando como flash message.
  - `POST /prontuario/{id}/tratamento` -> Altera o tratamento atual do assistido.
- **Migrations Flyway**: V1 (tabelas iniciais), V2 (tabela `avaliacao`, que nunca tinha sido criada de fato), V3 (tabela `tipo_tratamento`, seedada com os 9 tratamentos do NAE), V4 (FK `assistido.tratamento_atual_id` -> `tipo_tratamento`).

## 5. Front-end (Thymeleaf/Templates)
- **`index.html`**: Tabela com a listagem de todos os assistidos cadastrados (incluindo tratamento atual) e botão para novo cadastro.
- **`form.html`**: Formulário de registro usando classes Bootstrap, incluindo seleção do tratamento atual.
- **`prontuario.html`**: Tela de detalhes que imita a ficha original — "Dados do Assistido", card de "Tratamento Espiritual Atual" (com descrição e formulário para alterar) e "Histórico de Tratamento" (tabela de checkboxes) com botão funcional para registrar nova sessão.
- **`sessao-form.html`**: Formulário de nova sessão com os 7 checkboxes de recomendações, data e observações.

## 6. Cuidados / Armadilhas Já Encontradas
- **Path variable `id` + `@ModelAttribute` com campo `id`**: o `ExtendedServletRequestDataBinder` do Spring MVC injeta a variável de path automaticamente em qualquer propriedade do command object com o mesmo nome. Isso já causou um bug (POST de nova sessão virando `UPDATE` inexistente). Regra: nunca nomear um `@PathVariable` como `id` na mesma rota que recebe via `@ModelAttribute` uma entidade que tenha um campo `id`.
- **Flyway baseline**: o banco de dev foi originalmente criado via `ddl-auto=update` e depois baselineado no Flyway (V1) sem que o schema real batesse 100% com o script — por isso a tabela `avaliacao` nunca existiu de fato. Ao adicionar uma entidade nova, sempre conferir contra o banco real (`\dt`, `\d <tabela>`) antes de assumir que a migration já rodou.

## 7. Próximos Passos
1. Iniciar o desenvolvimento do módulo de `Avaliacao` (tela de nova avaliação a cada 4 sessões, ligada à regra já existente no `TratamentoService`).
2. Avaliar se `TipoTratamento` deve também ser sugerido/registrado por `Avaliacao` (hoje o vínculo é só `Assistido.tratamentoAtual`).