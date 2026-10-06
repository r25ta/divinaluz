-- Preletor convidado no cadastro geral (2026-10-05, pedido do responsável do projeto, no mesmo dia da
-- V40): o convidado passa a ser cadastrado pelo "Novo Cadastro", como um registro de assistido com
-- vínculo CONVIDADO — só dados de contato, sem tratamento, cartão ou login, e fora da listagem de
-- prontuários. A tabela preletor_convidado da V40 fica sem uso (convenção das colunas legadas).

-- Contato do convidado (o cadastro de assistido não tinha telefone nem origem).
ALTER TABLE assistido
    ADD COLUMN telefone VARCHAR(30),
    ADD COLUMN origem VARCHAR(150);

-- Cada convidado da V40 vira um assistido CONVIDADO. O e-mail só vai junto se não estiver em outro
-- cadastro (o e-mail é único desde a V35); o original continua guardado em preletor_convidado.
ALTER TABLE preletor_convidado ADD COLUMN assistido_id BIGINT;

DO $$
DECLARE
    c RECORD;
    novo BIGINT;
BEGIN
    FOR c IN SELECT * FROM preletor_convidado ORDER BY id LOOP
        INSERT INTO assistido (nome, email, telefone, origem, vinculo, ativo)
        VALUES (
            c.nome,
            CASE WHEN c.email IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM assistido a WHERE lower(a.email) = lower(c.email))
                 THEN lower(c.email) END,
            c.telefone, c.origem, 'CONVIDADO', c.ativo)
        RETURNING id INTO novo;
        UPDATE preletor_convidado SET assistido_id = novo WHERE id = c.id;
    END LOOP;
END $$;

-- A preleção passa a apontar para o assistido CONVIDADO (mesma coluna convidado_id).
ALTER TABLE prelecao DROP CONSTRAINT fk_prelecao_convidado;
UPDATE prelecao p SET convidado_id = pc.assistido_id
    FROM preletor_convidado pc WHERE p.convidado_id = pc.id;
ALTER TABLE prelecao
    ADD CONSTRAINT fk_prelecao_convidado FOREIGN KEY (convidado_id) REFERENCES assistido(id);

-- Troca emergencial do preletor na sessão: também por um convidado (o substituto trabalhador
-- continua em preletor_substituto_id; no máximo um dos dois).
ALTER TABLE sessao_assistencia
    ADD COLUMN convidado_substituto_id BIGINT,
    ADD CONSTRAINT fk_sessao_convidado_substituto
        FOREIGN KEY (convidado_substituto_id) REFERENCES assistido(id),
    ADD CONSTRAINT ck_sessao_um_substituto
        CHECK (num_nonnulls(preletor_substituto_id, convidado_substituto_id) <= 1);
