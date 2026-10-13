-- Auditoria (2026-10-12, pedido do responsável do projeto): toda inclusão, alteração e exclusão
-- gravada pelo sistema, com quem fez, o que fez, o valor anterior e o novo, e quando. Só o
-- Administrador consulta (/auditoria).
--
-- Gravada por config/AuditoriaListener (eventos do Hibernate), na MESMA transação da ação: se a
-- ação é desfeita, o registro dela também. Sem entidade JPA de propósito — uma entidade aqui seria
-- ela mesma auditada pelo listener.

CREATE TABLE auditoria (
    id                  BIGSERIAL PRIMARY KEY,
    data_hora           TIMESTAMP    NOT NULL,
    usuario_login       VARCHAR(120),
    acao                VARCHAR(10)  NOT NULL,   -- INCLUSAO, ALTERACAO, EXCLUSAO
    entidade            VARCHAR(60)  NOT NULL,   -- nome da classe (Assistido, Avaliacao, ...)
    registro_id         VARCHAR(40),
    registro_descricao  VARCHAR(200),
    assistido_id        BIGINT,                  -- de quem é o registro, quando houver (sem FK: o histórico sobrevive)
    origem              VARCHAR(250),            -- "POST /prontuario/12/editar", ou "sistema"
    CONSTRAINT ck_auditoria_acao CHECK (acao IN ('INCLUSAO', 'ALTERACAO', 'EXCLUSAO'))
);

CREATE INDEX ix_auditoria_data_hora ON auditoria (data_hora DESC);
CREATE INDEX ix_auditoria_assistido ON auditoria (assistido_id);
CREATE INDEX ix_auditoria_usuario ON auditoria (usuario_login);

CREATE TABLE auditoria_campo (
    id              BIGSERIAL PRIMARY KEY,
    auditoria_id    BIGINT       NOT NULL REFERENCES auditoria (id) ON DELETE CASCADE,
    campo           VARCHAR(80)  NOT NULL,
    valor_anterior  TEXT,
    valor_novo      TEXT
);

CREATE INDEX ix_auditoria_campo_auditoria ON auditoria_campo (auditoria_id);
