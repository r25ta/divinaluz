-- =============================================================================================
-- Remove a carga de simulação: apaga tudo o que carga-simulacao.sql gravou (listado em
-- simulacao_registro) e também o que o sistema gravou depois para as pessoas simuladas (uma presença
-- carimbada numa sessão real, uma avaliação, um celular vinculado...). Os dados reais ficam.
-- Roda numa transação só: ou sai tudo, ou nada.
-- Uso: psql "<connection string>" -f remover-simulacao.sql
-- =============================================================================================
\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';
BEGIN;

DO $$
BEGIN
  IF to_regclass('public.simulacao_registro') IS NULL THEN
    RAISE EXCEPTION 'Não há simulação carregada neste banco (a tabela simulacao_registro não existe).';
  END IF;
END $$;

CREATE TEMP TABLE sim_assistido ON COMMIT DROP AS
    SELECT registro_id AS id FROM simulacao_registro WHERE tabela = 'assistido';
CREATE TEMP TABLE sim_trabalhador ON COMMIT DROP AS
    SELECT id FROM trabalhador WHERE assistido_id IN (SELECT id FROM sim_assistido);
CREATE TEMP TABLE sim_prelecao ON COMMIT DROP AS
    SELECT registro_id AS id FROM simulacao_registro WHERE tabela = 'prelecao';
CREATE TEMP TABLE sim_sessao ON COMMIT DROP AS
    SELECT registro_id AS id FROM simulacao_registro WHERE tabela = 'sessao_assistencia';

-- O que não dá para resolver sozinho: registros REAIS que dependem de uma pessoa simulada e que
-- apagar levaria junto um dado de verdade. Aí a remoção para e diz o que é.
DO $$
DECLARE problema TEXT;
BEGIN
  SELECT string_agg(to_char(p.data_apresentacao, 'DD/MM/YYYY') || ' (' || p.tema || ')', ', ') INTO problema
    FROM prelecao p
   WHERE p.id NOT IN (SELECT id FROM sim_prelecao)
     AND (p.trabalhador_id IN (SELECT id FROM sim_trabalhador) OR p.convidado_id IN (SELECT id FROM sim_assistido));
  IF problema IS NOT NULL THEN
    RAISE EXCEPTION 'Há preleções reais com preletor da simulação: %. Troque o preletor delas e rode de novo.', problema;
  END IF;

  SELECT string_agg(a.nome, ', ') INTO problema FROM (
      SELECT x.assistido_id FROM avaliacao x WHERE x.avaliador_id IN (SELECT id FROM sim_assistido)
         AND x.assistido_id NOT IN (SELECT id FROM sim_assistido)
      UNION
      SELECT x.assistido_id FROM entrevista x WHERE x.entrevistador_id IN (SELECT id FROM sim_assistido)
         AND x.assistido_id NOT IN (SELECT id FROM sim_assistido)) r
    JOIN assistido a ON a.id = r.assistido_id;
  IF problema IS NOT NULL THEN
    RAISE EXCEPTION 'Há avaliações ou entrevistas de pessoas reais registradas por um cadastro da simulação: %.', problema;
  END IF;
END $$;

-- Referências de registros reais a registros simulados que podem só ser desligadas.
UPDATE sessao_assistencia SET preletor_substituto_id = NULL, tema_substituto = NULL
 WHERE preletor_substituto_id IN (SELECT id FROM sim_trabalhador) AND id NOT IN (SELECT id FROM sim_sessao);
UPDATE sessao_assistencia SET convidado_substituto_id = NULL, tema_substituto = NULL
 WHERE convidado_substituto_id IN (SELECT id FROM sim_assistido) AND id NOT IN (SELECT id FROM sim_sessao);
UPDATE sessao_tratamento SET prelecao_id = NULL
 WHERE prelecao_id IN (SELECT id FROM sim_prelecao) AND assistido_id NOT IN (SELECT id FROM sim_assistido);

-- A escala real perde só as posições ocupadas por trabalhadores simulados.
DELETE FROM sessao_escala WHERE trabalhador_id IN (SELECT id FROM sim_trabalhador) OR sessao_id IN (SELECT id FROM sim_sessao);

-- Tudo o que pertence às pessoas simuladas.
DELETE FROM entrevista WHERE assistido_id IN (SELECT id FROM sim_assistido)
    OR avaliacao_id IN (SELECT id FROM avaliacao WHERE assistido_id IN (SELECT id FROM sim_assistido));
DELETE FROM avaliacao WHERE assistido_id IN (SELECT id FROM sim_assistido);
DELETE FROM sessao_tratamento WHERE assistido_id IN (SELECT id FROM sim_assistido);
DELETE FROM cartao_encerrado WHERE assistido_id IN (SELECT id FROM sim_assistido);
DELETE FROM historico_dia_frequencia WHERE assistido_id IN (SELECT id FROM sim_assistido);
DELETE FROM celular_vinculado WHERE assistido_id IN (SELECT id FROM sim_assistido);
DELETE FROM persistent_logins WHERE username IN (SELECT login FROM assistido WHERE id IN (SELECT id FROM sim_assistido) AND login IS NOT NULL);

-- Preleções e sessões da carga (a escala delas já saiu acima). Presenças reais nessas datas, se
-- houver, ficam: a presença não aponta para a sessão, só para a data.
UPDATE sessao_tratamento SET prelecao_id = NULL WHERE prelecao_id IN (SELECT id FROM sim_prelecao);
DELETE FROM prelecao WHERE id IN (SELECT id FROM sim_prelecao);
DELETE FROM sessao_assistencia WHERE id IN (SELECT id FROM sim_sessao);

DELETE FROM trabalhador_funcao WHERE trabalhador_id IN (SELECT id FROM sim_trabalhador);
DELETE FROM trabalhador WHERE id IN (SELECT id FROM sim_trabalhador);
DELETE FROM assistido WHERE id IN (SELECT id FROM sim_assistido);

SELECT (SELECT count(*) FROM sim_assistido) AS cadastros_removidos,
       (SELECT count(*) FROM sim_sessao) AS sessoes_removidas,
       (SELECT count(*) FROM sim_prelecao) AS prelecoes_removidas;

DROP TABLE simulacao_registro;
COMMIT;
