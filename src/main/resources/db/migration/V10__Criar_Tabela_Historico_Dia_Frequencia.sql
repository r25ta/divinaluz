-- Log de rastreabilidade das alterações de dia/horário de assistência espiritual
-- (item 3 da especificação: toda troca de dia precisa ficar registrada).
CREATE TABLE historico_dia_frequencia (
    id BIGSERIAL PRIMARY KEY,
    assistido_id BIGINT NOT NULL,
    dia_anterior VARCHAR(20),
    dia_novo VARCHAR(20),
    data_hora TIMESTAMP NOT NULL,
    motivo TEXT,

    CONSTRAINT fk_historico_dia_frequencia_assistido
        FOREIGN KEY (assistido_id)
        REFERENCES assistido(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_historico_dia_frequencia_assistido ON historico_dia_frequencia(assistido_id);
