-- Idade deixa de ser digitada manualmente e passa a ser calculada em tempo real a partir
-- da data de nascimento (ver Assistido.getIdade()). A coluna "idade" antiga é mantida na
-- tabela (não é mais mapeada pela entidade) para não perder o histórico já registrado.
ALTER TABLE assistido ADD COLUMN data_nascimento DATE;
