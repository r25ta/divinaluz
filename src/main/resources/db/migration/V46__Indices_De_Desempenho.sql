-- Índices para as consultas mais frequentes (revisão de 2026-10-13). Só cria, não altera dado nenhum,
-- e IF NOT EXISTS porque o banco de dev foi baselineado à parte e pode já ter algum deles.

-- Cartão, prontuário e regra semanal: todas as presenças de UMA pessoa, ordenadas por data. O índice só
-- por assistido_id (V1) achava as linhas, mas a ordenação e o filtro de data eram feitos depois.
CREATE INDEX IF NOT EXISTS idx_sessao_assistido_data ON sessao_tratamento (assistido_id, data_consulta);

-- Excluir uma preleção confere se há presença ligada a ela (SessaoRepository.existsByPrelecaoId), e
-- apagar uma preleção precisa achar as presenças que apontam para ela (FK sem índice = tabela inteira).
CREATE INDEX IF NOT EXISTS idx_sessao_prelecao ON sessao_tratamento (prelecao_id);

-- Indicador "Entrevistas · retorno" do painel da sessão, recalculado a cada 10s com a sessão aberta.
CREATE INDEX IF NOT EXISTS idx_entrevista_data_tipo ON entrevista (data, tipo);

-- Pendências do menu (número vermelho em "Prontuário") e filtros da listagem por status do cartão.
CREATE INDEX IF NOT EXISTS idx_assistido_status_cartao ON assistido (status_cartao) WHERE ativo;
