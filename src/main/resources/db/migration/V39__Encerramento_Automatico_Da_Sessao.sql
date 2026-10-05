-- Encerramento automático da sessão (2026-10-05): a sessão pode ficar aberta até 23:59:59 do próprio
-- dia; se a recepção não a encerrar, o sistema encerra (EncerramentoAutomaticoSessoes). Esta coluna
-- distingue, no painel e na lista, o encerramento feito pela recepção do feito pelo sistema.
ALTER TABLE sessao_assistencia
    ADD COLUMN encerrada_automaticamente BOOLEAN NOT NULL DEFAULT FALSE;
