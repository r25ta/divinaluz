ALTER TABLE sessao_tratamento
    ADD COLUMN prelecao_id BIGINT;

ALTER TABLE sessao_tratamento
    ADD CONSTRAINT fk_sessao_prelecao
    FOREIGN KEY (prelecao_id) REFERENCES prelecao(id);