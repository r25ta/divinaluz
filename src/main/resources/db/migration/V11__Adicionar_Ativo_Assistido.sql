-- Exclusão lógica do prontuário (item 1): "desativar" em vez de apagar, já que o assistido
-- pode voltar a fazer tratamento futuramente.
ALTER TABLE assistido ADD COLUMN ativo BOOLEAN NOT NULL DEFAULT TRUE;
