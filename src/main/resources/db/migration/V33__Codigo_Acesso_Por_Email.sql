-- Entrada por código de e-mail (ver CodigoAcessoService): quem tem e-mail no cadastro entra pedindo
-- um código de 6 dígitos, em vez de senha. O código é curto, então vale o oposto do token da V30:
-- vida curta (minutos), número de tentativas limitado e guardado como HASH — um código de 6 dígitos
-- em texto puro tornaria um dump do banco (há backup diário) acesso imediato a todas as contas.
ALTER TABLE assistido
    -- Hash BCrypt do código em vigor (60 caracteres; a folga é para não travar numa troca de algoritmo).
    ADD COLUMN codigo_acesso VARCHAR(100),
    ADD COLUMN codigo_acesso_expira_em TIMESTAMP,
    -- Tentativas erradas no código em vigor; ao atingir o limite o código é descartado. INTEGER (e não
    -- SMALLINT, que bastaria para contar até 5) porque o campo é int na entidade e o ddl-auto=validate
    -- recusa int2 onde espera int4.
    ADD COLUMN codigo_acesso_tentativas INTEGER NOT NULL DEFAULT 0,
    -- Momento do último envio, usado só para o intervalo mínimo entre pedidos (anti-bomba de e-mail).
    ADD COLUMN codigo_acesso_enviado_em TIMESTAMP;

-- "Lembrar deste aparelho": sem isso, entrar por código exigiria uma ida ao e-mail toda semana, o que
-- seria MAIS atrito que a senha, não menos. O token fica no banco (e não assinado com a senha, como
-- faz a variante em memória do Spring Security) justamente porque quem entra por código não tem senha.
-- Nomes de tabela e colunas são os que JdbcTokenRepositoryImpl espera — não renomear.
CREATE TABLE persistent_logins (
    series    VARCHAR(64) PRIMARY KEY,
    username  VARCHAR(80) NOT NULL,
    token     VARCHAR(64) NOT NULL,
    last_used TIMESTAMP   NOT NULL
);

CREATE INDEX idx_persistent_logins_username ON persistent_logins (username);
