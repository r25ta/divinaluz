-- Tipos de entrevista (2026-10-08, ver CLAUDE.md 3.25). Além da entrevista que segue a Avaliação e
-- decide o tratamento (TRATAMENTO, a única que existia), o prontuário passa a registrar a entrevista
-- do dia da 1ª sessão (PRIMEIRA_SESSAO, se o assistido quiser) e a pedida por ele a qualquer momento
-- (EXCEPCIONAL). Essas duas não têm avaliação, resultado nem tratamento, e não liberam o cartão.
-- As entrevistas existentes são todas de tratamento: o DEFAULT as classifica.

ALTER TABLE entrevista ADD COLUMN tipo VARCHAR(20) NOT NULL DEFAULT 'TRATAMENTO';

ALTER TABLE entrevista
    ADD CONSTRAINT ck_entrevista_tipo CHECK (tipo IN ('TRATAMENTO', 'PRIMEIRA_SESSAO', 'EXCEPCIONAL'));

-- A avaliação continua obrigatória (e única, pela uk que já existe) na entrevista de tratamento, e
-- proibida nas outras.
ALTER TABLE entrevista ALTER COLUMN avaliacao_id DROP NOT NULL;
ALTER TABLE entrevista
    ADD CONSTRAINT ck_entrevista_avaliacao_do_tipo CHECK ((tipo = 'TRATAMENTO') = (avaliacao_id IS NOT NULL));

-- Novo tratamento x alta só faz sentido na entrevista de tratamento.
ALTER TABLE entrevista ALTER COLUMN resultado DROP NOT NULL;
ALTER TABLE entrevista
    ADD CONSTRAINT ck_entrevista_resultado_do_tipo CHECK ((tipo = 'TRATAMENTO') = (resultado IS NOT NULL));

CREATE INDEX idx_entrevista_assistido_tipo_data ON entrevista (assistido_id, tipo, data);
