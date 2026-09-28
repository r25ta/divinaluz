-- Remove as colunas legadas órfãs citadas no item 6 (Cuidados/Armadilhas) e no item 3 dos
-- Próximos Passos do CLAUDE.md: nenhuma delas é mapeada pelas entidades atuais e os dados já
-- foram migrados para os campos que as substituíram (dataNascimento/getIdade() calculado, e
-- Entrevista.entrevistador/tratamentoIndicado, criados na V13).
-- IF EXISTS por causa do desalinhamento conhecido do baseline (ver seção 6 do CLAUDE.md).

-- assistido.idade -> substituída por assistido.data_nascimento (V8), Idade é @Transient
ALTER TABLE assistido DROP COLUMN IF EXISTS idade;

-- avaliacao.entrevistador / avaliacao.tratamento_indicado_id -> substituídas pela tabela
-- entrevista (V13); o DROP COLUMN remove junto a FK fk_avaliacao_tratamento_indicado (V5).
ALTER TABLE avaliacao DROP COLUMN IF EXISTS entrevistador;
ALTER TABLE avaliacao DROP COLUMN IF EXISTS tratamento_indicado_id;
