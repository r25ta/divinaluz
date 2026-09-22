-- Entrevista: passo seguinte à Avaliação, em que o entrevistador comunica ao assistido o
-- tratamento decidido. Fica em tabela própria (1:1 com avaliacao) porque, ao contrário da
-- avaliação, a data da entrevista precisa cair no dia de assistência do assistido (item 3).
-- As colunas "entrevistador" e "tratamento_indicado_id" de avaliacao deixam de ser usadas pela
-- aplicação (permanecem na tabela para não perder histórico já registrado).
CREATE TABLE entrevista (
    id BIGSERIAL PRIMARY KEY,
    avaliacao_id BIGINT NOT NULL UNIQUE,
    assistido_id BIGINT NOT NULL,
    data DATE,
    entrevistador VARCHAR(255),
    tratamento_indicado_id BIGINT,

    CONSTRAINT fk_entrevista_avaliacao
        FOREIGN KEY (avaliacao_id)
        REFERENCES avaliacao(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_entrevista_assistido
        FOREIGN KEY (assistido_id)
        REFERENCES assistido(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_entrevista_tratamento_indicado
        FOREIGN KEY (tratamento_indicado_id)
        REFERENCES tipo_tratamento(id)
);

CREATE INDEX idx_entrevista_assistido ON entrevista(assistido_id);
