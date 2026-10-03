-- Cancelamento da sessão de assistência (2026-10-03): quando a casa não abre (feriado, chuva,
-- imprevisto) a sessão fica registrada como cancelada, com o motivo, em vez de sumir. As presenças
-- da data são desfeitas no cancelamento, e a semana cancelada não conta na regra dos 21 dias de
-- ausência (ver TratamentoService). Sessão aberta por engano continua podendo ser excluída.
ALTER TABLE sessao_assistencia
    ADD COLUMN cancelada_em TIMESTAMP,
    ADD COLUMN motivo_cancelamento VARCHAR(255);
