CREATE TABLE usuario (
    id BIGSERIAL PRIMARY KEY,
    login VARCHAR(80) NOT NULL UNIQUE,
    senha VARCHAR(255) NOT NULL,
    perfil VARCHAR(20) NOT NULL,
    assistido_id BIGINT UNIQUE,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_usuario_assistido FOREIGN KEY (assistido_id) REFERENCES assistido(id)
);

-- Credencial provisoria do ambiente inicial: admin / admin123.
-- Deve ser alterada antes de qualquer uso fora do ambiente de desenvolvimento.
INSERT INTO usuario (login, senha, perfil, ativo)
VALUES ('admin', '$2a$10$8XqY9QzM9mH0M8cXy3uJ9eG5J8Qk6dL9V2pR7sT4wN1xY6zA3bC4D', 'ADMINISTRADOR', TRUE);