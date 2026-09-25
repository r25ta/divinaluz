-- Usuario e Assistido eram a mesma pessoa em duas tabelas (com e-mail duplicado). Os dados de
-- acesso passam a viver no próprio assistido; a tabela "usuario" é mantida sem uso (mesma
-- convenção das colunas legadas — ver CLAUDE.md item 6) para não perder dados.
ALTER TABLE assistido
    ADD COLUMN login VARCHAR(80),
    ADD COLUMN senha VARCHAR(255),
    ADD COLUMN perfil_acesso VARCHAR(20),
    ADD COLUMN provedor VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN identificador_externo VARCHAR(255),
    ADD COLUMN acesso_ativo BOOLEAN NOT NULL DEFAULT TRUE;

CREATE UNIQUE INDEX uk_assistido_login ON assistido (login) WHERE login IS NOT NULL;
CREATE UNIQUE INDEX uk_assistido_provedor_externo
    ON assistido (provedor, identificador_externo) WHERE identificador_externo IS NOT NULL;

-- Usuários sem assistido (ex.: admin) passam a ter um assistido próprio, vínculo TRABALHADOR.
WITH novos AS (
    INSERT INTO assistido (nome, vinculo, ativo, status_cartao, email)
    SELECT u.login, 'TRABALHADOR', TRUE, 'EM_TRATAMENTO', u.email
    FROM usuario u
    WHERE u.assistido_id IS NULL
    RETURNING id, nome
)
UPDATE usuario u
SET assistido_id = n.id
FROM novos n
WHERE u.assistido_id IS NULL AND u.login = n.nome;

UPDATE assistido a
SET login = u.login,
    senha = u.senha,
    perfil_acesso = u.perfil,
    provedor = u.provedor,
    identificador_externo = u.identificador_externo,
    acesso_ativo = u.ativo,
    email = COALESCE(a.email, u.email)
FROM usuario u
WHERE u.assistido_id = a.id;
