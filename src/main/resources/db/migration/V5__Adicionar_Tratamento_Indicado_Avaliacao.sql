ALTER TABLE avaliacao ADD COLUMN tratamento_indicado_id BIGINT;

ALTER TABLE avaliacao ADD CONSTRAINT fk_avaliacao_tratamento_indicado
    FOREIGN KEY (tratamento_indicado_id) REFERENCES tipo_tratamento(id);
