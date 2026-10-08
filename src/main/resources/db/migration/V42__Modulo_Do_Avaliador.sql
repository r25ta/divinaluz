-- Módulo do Avaliador (2026-10-07). Fiel ao cartão físico: cada "VEZ" da frente é uma Avaliação
-- (histórico, evolução M/P/I/B, data e entrevistador) e cada "Série" do verso ("Resultado da
-- Consulta") é o resultado dela — as 7 recomendações marcáveis e as observações.

-- 1) Resultado da Avaliação: novo tratamento (o de sempre) ou alta. As antigas eram todas novo tratamento.
ALTER TABLE avaliacao ADD COLUMN resultado VARCHAR(30) NOT NULL DEFAULT 'NOVO_TRATAMENTO';

-- 2) Recomendações do verso do cartão. Falso nas avaliações antigas (não existiam no sistema).
ALTER TABLE avaliacao ADD COLUMN rec_visto BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE avaliacao ADD COLUMN rec_assistencia BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE avaliacao ADD COLUMN rec_evangelho_no_lar BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE avaliacao ADD COLUMN rec_leituras BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE avaliacao ADD COLUMN rec_escola BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE avaliacao ADD COLUMN rec_trabalho_espiritual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE avaliacao ADD COLUMN rec_medico BOOLEAN NOT NULL DEFAULT FALSE;

-- 3) Quem registrou (auditoria): o login de quem salvou, nunca um nome digitado. Nulo nas antigas.
ALTER TABLE avaliacao ADD COLUMN avaliador_id BIGINT;
ALTER TABLE avaliacao
    ADD CONSTRAINT fk_avaliacao_avaliador FOREIGN KEY (avaliador_id) REFERENCES assistido (id);

ALTER TABLE entrevista ADD COLUMN resultado VARCHAR(30) NOT NULL DEFAULT 'NOVO_TRATAMENTO';
ALTER TABLE entrevista ADD COLUMN observacoes TEXT;
ALTER TABLE entrevista ADD COLUMN entrevistador_id BIGINT;
ALTER TABLE entrevista
    ADD CONSTRAINT fk_entrevista_entrevistador FOREIGN KEY (entrevistador_id) REFERENCES assistido (id);

-- 4) A "VEZ" passa a contar todas as avaliações da pessoa, como no cartão físico (1ª a 8ª VEZ).
--    Até aqui era contada dentro do ciclo, e como cada entrevista reinicia o ciclo, toda avaliação
--    saía "1ª VEZ". Renumera as existentes em ordem de data.
UPDATE avaliacao a
SET numero_vez = n.vez
FROM (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY assistido_id ORDER BY data, id) AS vez
    FROM avaliacao
) n
WHERE a.id = n.id;
