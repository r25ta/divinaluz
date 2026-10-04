-- Catálogo de funções redefinido e tratamento proposto na Avaliação (2026-10-04).
--
-- 1) Funções removidas do TipoTrabalhador. Isto precisa de migration: o @ElementCollection lê o
--    name() do enum, e uma linha com um nome que não existe mais quebraria a leitura do trabalhador.
--    Decisão do responsável do projeto: quem era Secretária passa a ser Recepcionista (acessos
--    parecidos); Facilitador e Educador de Evangelização Infantil e Mocidade só saem.

INSERT INTO trabalhador_funcao (trabalhador_id, funcao)
SELECT DISTINCT f.trabalhador_id, 'RECEPCIONISTA'
FROM trabalhador_funcao f
WHERE f.funcao = 'SECRETARIA'
  AND NOT EXISTS (
      SELECT 1 FROM trabalhador_funcao g
      WHERE g.trabalhador_id = f.trabalhador_id AND g.funcao = 'RECEPCIONISTA'
  );

-- Quem ficar sem nenhuma função volta a ser só assistido — o mesmo que TrabalhadorService.definirPerfis
-- faz ao desmarcar todas: vínculo ASSISTIDO e, se o perfil do acesso era TRABALHADOR, ASSISTIDO
-- (o ADMINISTRADOR não é mexido). A linha de "trabalhador" fica, como lá, pelo histórico de
-- preleções e escalas. O UPDATE enxerga a tabela de antes do DELETE (regra dos CTEs que alteram
-- dados), por isso o NOT EXISTS ignora as funções que estão saindo.
WITH removidas AS (
    DELETE FROM trabalhador_funcao f
    USING trabalhador t
    WHERE f.trabalhador_id = t.id
      AND f.funcao IN ('SECRETARIA', 'FACILITADOR', 'EDUCADOR_EVANGELIZACAO')
    RETURNING t.assistido_id
)
UPDATE assistido a
SET vinculo = 'ASSISTIDO',
    perfil_acesso = CASE WHEN a.perfil_acesso = 'TRABALHADOR' THEN 'ASSISTIDO' ELSE a.perfil_acesso END
WHERE a.id IN (SELECT assistido_id FROM removidas)
  AND NOT EXISTS (
      SELECT 1
      FROM trabalhador t2
      JOIN trabalhador_funcao f2 ON f2.trabalhador_id = t2.id
      WHERE t2.assistido_id = a.id
        AND f2.funcao NOT IN ('SECRETARIA', 'FACILITADOR', 'EDUCADOR_EVANGELIZACAO')
  );

-- 2) O Avaliador propõe o novo tratamento na própria Avaliação; a Entrevista já abre com ele e o
--    entrevistador confirma ou ajusta ao comunicar. Nulo nas avaliações antigas (antes disto o
--    tratamento só existia a partir da Entrevista).
ALTER TABLE avaliacao ADD COLUMN tratamento_proposto_id BIGINT;
ALTER TABLE avaliacao
    ADD CONSTRAINT fk_avaliacao_tratamento_proposto
    FOREIGN KEY (tratamento_proposto_id) REFERENCES tipo_tratamento (id);
