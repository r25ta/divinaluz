-- Presenças de ouvinte (SessaoTratamento.ouvinte = true) são gravadas com numero_serie = NULL,
-- pois não avançam o ciclo do cartão. A V1 declarou a coluna como NOT NULL; o banco de dev atual
-- já não tem essa constraint (baseline divergiu do script — ver CLAUDE.md item 6), mas um banco
-- criado do zero a partir das migrations quebraria ao registrar a 1ª presença de ouvinte.
ALTER TABLE sessao_tratamento ALTER COLUMN numero_serie DROP NOT NULL;
