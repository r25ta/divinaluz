-- Criação da tabela Assistido (Dados Cadastrais)
CREATE TABLE assistido (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(255) NOT NULL,
    residencia VARCHAR(255),
    idade INTEGER,
    estado_civil VARCHAR(50),
    sexo VARCHAR(2),
    email VARCHAR(255),
    vinculo VARCHAR(50) -- Ex: ASSISTIDO, TRABALHADOR, ALUNO
);

-- Criação da tabela Avaliacao (Controle de Vezes/Entrevistas)
CREATE TABLE avaliacao (
    id BIGSERIAL PRIMARY KEY,
    assistido_id BIGINT NOT NULL,
    numero_vez INTEGER NOT NULL, -- Corresponde a 1ª VEZ, 2ª VEZ, etc.
    data DATE,
    entrevistador VARCHAR(255),
    historico TEXT,
    evolucao VARCHAR(20), -- Ex: BOM, INDIFERENTE, PIOR, MELHOR
    
    CONSTRAINT fk_avaliacao_assistido 
        FOREIGN KEY (assistido_id) 
        REFERENCES assistido(id) 
        ON DELETE CASCADE
);

-- Criação da tabela SessaoTratamento (Resultado da Consulta)
CREATE TABLE sessao_tratamento (
    id BIGSERIAL PRIMARY KEY,
    assistido_id BIGINT NOT NULL,
    numero_serie INTEGER NOT NULL, -- Corresponde a 1ª SÉRIE, 2ª SÉRIE, etc.
    data_consulta DATE NOT NULL,
    
    -- Recomendações marcadas no cartão (Booleanos)
    visto BOOLEAN DEFAULT FALSE,
    assistencia BOOLEAN DEFAULT FALSE,
    evangelho_no_lar BOOLEAN DEFAULT FALSE,
    leituras BOOLEAN DEFAULT FALSE,
    escola BOOLEAN DEFAULT FALSE,
    trabalho_espiritual BOOLEAN DEFAULT FALSE,
    medico BOOLEAN DEFAULT FALSE,
    
    observacoes TEXT,
    
    CONSTRAINT fk_sessao_assistido 
        FOREIGN KEY (assistido_id) 
        REFERENCES assistido(id) 
        ON DELETE CASCADE
);

-- Criação de Índices para otimizar as buscas frequentes
-- (Especialmente importante para a validação de 1 sessão por semana)
CREATE INDEX idx_avaliacao_assistido ON avaliacao(assistido_id);
CREATE INDEX idx_sessao_assistido ON sessao_tratamento(assistido_id);
CREATE INDEX idx_sessao_data_consulta ON sessao_tratamento(data_consulta);