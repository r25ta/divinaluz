CREATE TABLE prelecao (
    id BIGSERIAL PRIMARY KEY,
    data_apresentacao DATE NOT NULL,
    tema VARCHAR(255) NOT NULL,
    trabalhador_id BIGINT NOT NULL,

    CONSTRAINT fk_prelecao_trabalhador
        FOREIGN KEY (trabalhador_id)
        REFERENCES trabalhador(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_prelecao_data_apresentacao ON prelecao(data_apresentacao);
CREATE INDEX idx_prelecao_trabalhador ON prelecao(trabalhador_id);
