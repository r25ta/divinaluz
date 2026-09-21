ALTER TABLE assistido ADD COLUMN ciclo_iniciado_em DATE;

-- Backfill para assistidos que já têm sessões registradas: assume a data da sessão mais
-- antiga como início do ciclo atual (não reconstrói hiatos passados de 3+ semanas).
UPDATE assistido a
SET ciclo_iniciado_em = (
    SELECT MIN(s.data_consulta) FROM sessao_tratamento s WHERE s.assistido_id = a.id
)
WHERE EXISTS (SELECT 1 FROM sessao_tratamento s WHERE s.assistido_id = a.id);
