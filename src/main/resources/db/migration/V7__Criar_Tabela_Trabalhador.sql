-- Perfil de trabalhador: todo trabalhador é um assistido, mas nem todo assistido é
-- trabalhador. Por isso o cadastro fica em tabela própria, ligada 1:1 ao assistido,
-- em vez de sobrecarregar a tabela assistido com colunas que só fazem sentido para quem
-- exerce alguma função na casa espírita.
CREATE TABLE trabalhador (
    id BIGSERIAL PRIMARY KEY,
    assistido_id BIGINT NOT NULL UNIQUE,

    CONSTRAINT fk_trabalhador_assistido
        FOREIGN KEY (assistido_id)
        REFERENCES assistido(id)
        ON DELETE CASCADE
);

-- Um trabalhador pode acumular mais de uma função (ex: Dirigente e Passista ao mesmo
-- tempo), por isso as funções ficam em tabela separada em vez de uma única coluna.
CREATE TABLE trabalhador_funcao (
    trabalhador_id BIGINT NOT NULL,
    funcao VARCHAR(60) NOT NULL,

    CONSTRAINT fk_trabalhador_funcao_trabalhador
        FOREIGN KEY (trabalhador_id)
        REFERENCES trabalhador(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_trabalhador_funcao_trabalhador ON trabalhador_funcao(trabalhador_id);
