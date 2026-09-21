ALTER TABLE assistido ADD COLUMN tratamento_atual_id BIGINT;

ALTER TABLE assistido ADD CONSTRAINT fk_assistido_tratamento_atual
    FOREIGN KEY (tratamento_atual_id) REFERENCES tipo_tratamento(id);
