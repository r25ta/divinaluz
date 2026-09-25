-- Check-in por QR code (ver CLAUDE.md 3.12).
--
-- 1) Cada assistido ganha um código opaco que identifica o cartão dele no QR. Não usamos o próprio
--    id na URL do QR para não expor/permitir adivinhar prontuários por sequência.
ALTER TABLE assistido ADD COLUMN codigo_cartao VARCHAR(36);
ALTER TABLE assistido ADD CONSTRAINT uk_assistido_codigo_cartao UNIQUE (codigo_cartao);

-- Gera o código de quem já está cadastrado; os novos recebem no primeiro acesso ao cartão.
UPDATE assistido SET codigo_cartao = gen_random_uuid()::text WHERE codigo_cartao IS NULL;

-- 2) Janela de check-in da sessão: a recepção abre no início da assistência e fecha no fim, e o QR
--    só carimba presença enquanto estiver aberta na data daquela preleção.
ALTER TABLE prelecao ADD COLUMN checkin_aberto_em TIMESTAMP;
ALTER TABLE prelecao ADD COLUMN checkin_fechado_em TIMESTAMP;
