-- Encerramento da sessão e QR da sessão para o próprio assistido marcar presença (2026-10-04).
--
-- encerrada_em: "Encerrar sessão" consolida o dia (fim do trabalho): fecha o check-in e trava
-- presenças, escala e preletor. Diferente de cancelada_em (V34), que é a casa não ter aberto.
--
-- checkin_segredo: semente do QR da sessão. O QR mostrado na recepção muda a cada minuto (o código
-- é um HMAC deste segredo com o minuto atual), para uma foto dele mandada por mensagem não servir
-- para marcar presença de casa. Fica no banco, e não na memória, para sobreviver ao reinício da
-- aplicação (o plano gratuito do Render hiberna a cada 15 minutos).
ALTER TABLE sessao_assistencia
    ADD COLUMN encerrada_em TIMESTAMP,
    ADD COLUMN checkin_segredo VARCHAR(64);

-- Sessões que já tiveram o check-in fechado são, na prática, sessões encerradas.
UPDATE sessao_assistencia SET encerrada_em = checkin_fechado_em WHERE checkin_fechado_em IS NOT NULL;
