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

## 4. Estrutura do Projeto Implementada até o Momento
- **`application.properties`**: Configurado para PostgreSQL na porta 5432, aplicação rodando na porta 8081 com contexto `/divinaluz`. O Spring Security foi removido temporariamente para facilitar os testes.
- **Pacote `model`**: Classes `Assistido` e `SessaoTratamento` mapeadas com JPA (`@Entity`).
- **Pacote `repository`**: Interfaces `AssistidoRepository` e `SessaoRepository` configuradas.
- **Pacote `service`**: `TratamentoService` esboçado com as lógicas de validação de dias e contagem de séries.
- **Pacote `controller`**: `ProntuarioController` configurado com rotas:
  - `GET /` -> Lista todos os assistidos.
  - `GET /novo` -> Abre formulário de cadastro.
  - `POST /salvar` -> Salva novo assistido.
  - `GET /prontuario/{id}` -> Exibe os detalhes e histórico de sessões do paciente.

## 5. Front-end (Thymeleaf/Templates)
- **`index.html`**: Tabela com a listagem de todos os assistidos cadastrados e botão para novo cadastro.
- **`form.html`**: Formulário de registro usando classes Bootstrap para visual limpo.
- **`prontuario.html`**: Tela de detalhes que imita a ficha original, dividida em "Dados do Assistido" no topo e "Histórico de Tratamento" (tabela de checkboxes) embaixo.

## 6. Próximos Passos (Para Amanhã)
1. Ativar o botão "Registrar Nova Sessão" na tela de detalhes do prontuário.
2. Criar o formulário Modal (ou nova página) para salvar uma sessão, incluindo os checkboxes.
3. Ligar esse formulário de sessão à regra de negócio no `TratamentoService` (bloquear se < 7 dias).
4. Iniciar o desenvolvimento do módulo de `Avaliacao`.