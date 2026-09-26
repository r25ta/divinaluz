-- Módulo Sessão (CLAUDE.md item 6 da seção 8).
--
-- 1) A sessão de assistência (Domingo 08h / Terça 19h) passa a ser um objeto próprio, separado da
--    preleção: é ela que tem a janela de check-in, a escala de trabalhadores e os indicadores. A
--    preleção da mesma data é encontrada pela data (não há FK), então a escala de preletores deixa
--    de ser pré-requisito para abrir o check-in. Por regra existe no máximo uma sessão por data.
CREATE TABLE sessao_assistencia (
    id BIGSERIAL PRIMARY KEY,
    data DATE NOT NULL,
    dia_frequencia VARCHAR(20) NOT NULL,
    -- Troca emergencial do preletor/tema só nesta sessão, sem reescrever a escala de preleções.
    preletor_substituto_id BIGINT,
    tema_substituto VARCHAR(255),
    checkin_aberto_em TIMESTAMP,
    checkin_fechado_em TIMESTAMP,

    CONSTRAINT uk_sessao_assistencia_data UNIQUE (data),
    CONSTRAINT fk_sessao_assistencia_preletor_substituto
        FOREIGN KEY (preletor_substituto_id) REFERENCES trabalhador(id) ON DELETE SET NULL
);

-- 2) Escala de trabalhadores da sessão: um trabalhador por posição (pode ocupar mais de uma).
CREATE TABLE sessao_escala (
    id BIGSERIAL PRIMARY KEY,
    sessao_id BIGINT NOT NULL,
    trabalhador_id BIGINT NOT NULL,
    posicao VARCHAR(40) NOT NULL,

    CONSTRAINT uk_sessao_escala UNIQUE (sessao_id, trabalhador_id, posicao),
    CONSTRAINT fk_sessao_escala_sessao
        FOREIGN KEY (sessao_id) REFERENCES sessao_assistencia(id) ON DELETE CASCADE,
    CONSTRAINT fk_sessao_escala_trabalhador
        FOREIGN KEY (trabalhador_id) REFERENCES trabalhador(id) ON DELETE CASCADE
);

CREATE INDEX idx_sessao_escala_sessao ON sessao_escala(sessao_id);

-- 3) Janelas de check-in que já existiam na preleção viram sessões. As colunas
--    prelecao.checkin_aberto_em/checkin_fechado_em ficam no banco sem uso (convenção das colunas
--    legadas, CLAUDE.md seção 6).
INSERT INTO sessao_assistencia (data, dia_frequencia, checkin_aberto_em, checkin_fechado_em)
SELECT data_apresentacao,
       CASE WHEN EXTRACT(DOW FROM data_apresentacao) = 2 THEN 'TERCA_19H' ELSE 'DOMINGO_08H' END,
       checkin_aberto_em,
       checkin_fechado_em
FROM prelecao
WHERE checkin_aberto_em IS NOT NULL
  AND EXTRACT(DOW FROM data_apresentacao) IN (0, 2);
