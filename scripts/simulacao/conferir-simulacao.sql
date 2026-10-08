-- =============================================================================================
-- Confere, direto no banco, que a carga de simulação respeita as regras do sistema.
-- Só leitura. Cada linha é uma regra; "problemas" precisa dar 0 em todas.
-- Uso: psql "<connection string>" -f conferir-simulacao.sql
-- =============================================================================================
\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';

CREATE TEMP VIEW sim_pessoa AS
    SELECT a.* FROM assistido a
    JOIN simulacao_registro r ON r.tabela = 'assistido' AND r.registro_id = a.id
    WHERE a.vinculo <> 'CONVIDADO';

CREATE TEMP VIEW sim_sessao AS
    SELECT s.* FROM sessao_assistencia s
    JOIN simulacao_registro r ON r.tabela = 'sessao_assistencia' AND r.registro_id = s.id;

CREATE TEMP VIEW sim_presenca AS
    SELECT t.* FROM sessao_tratamento t JOIN sim_pessoa p ON p.id = t.assistido_id;

-- Dia de assistência que valia em cada data (o histórico guarda toda troca).
CREATE TEMP VIEW sim_dia_em AS
    SELECT t.id AS presenca_id,
           (SELECT h.dia_novo FROM historico_dia_frequencia h
             WHERE h.assistido_id = t.assistido_id AND h.data_hora::date <= t.data_consulta
             ORDER BY h.data_hora DESC, h.id DESC LIMIT 1) AS dia
    FROM sim_presenca t;

-- Efetivas com o número da presença dentro do ciclo e a efetiva anterior da mesma pessoa.
CREATE TEMP VIEW sim_efetiva AS
    SELECT t.*, lag(t.data_consulta) OVER w AS anterior, lag(t.numero_serie) OVER w AS numero_anterior
    FROM sim_presenca t WHERE NOT t.ouvinte
    WINDOW w AS (PARTITION BY t.assistido_id ORDER BY t.data_consulta);

SELECT verificacao, problemas FROM (
  SELECT 1 AS ordem, 'Sessões abertas com 45 a 50 pessoas presentes' AS verificacao,
         count(*) FILTER (WHERE n NOT BETWEEN 45 AND 50) AS problemas
    FROM (SELECT s.data, count(DISTINCT t.assistido_id) AS n FROM sim_sessao s
          LEFT JOIN sim_presenca t ON t.data_consulta = s.data
          WHERE s.cancelada_em IS NULL GROUP BY s.data) x
  UNION ALL
  SELECT 2, 'Nenhuma presença em sessão cancelada',
         count(*) FROM sim_presenca t JOIN sim_sessao s ON s.data = t.data_consulta WHERE s.cancelada_em IS NOT NULL
  UNION ALL
  SELECT 3, 'Escala com o mínimo de cada setor (PosicaoSessao)',
         count(*) FROM sim_sessao s CROSS JOIN (VALUES ('DIRIGENTE',1),('RECEPCIONISTA',2),('SECRETARIA',1),
              ('ENTREVISTADOR',1),('PASSE_LIMPEZA',1),('CAMARA_PASSE',4),('DIRIGENTE_CAMARA',1),('P3B',3)) m(posicao, minimo)
         WHERE (SELECT count(*) FROM sessao_escala e WHERE e.sessao_id = s.id AND e.posicao = m.posicao) < m.minimo
  UNION ALL
  SELECT 4, 'Trabalhador escalado está presente na sessão',
         count(*) FROM sim_sessao s JOIN sessao_escala e ON e.sessao_id = s.id JOIN trabalhador w ON w.id = e.trabalhador_id
         WHERE s.cancelada_em IS NULL
           AND NOT EXISTS (SELECT 1 FROM sim_presenca t WHERE t.assistido_id = w.assistido_id AND t.data_consulta = s.data)
  UNION ALL
  SELECT 5, 'No máximo uma presença efetiva por semana (domingo a sábado)',
         count(*) FROM (SELECT assistido_id, date_trunc('week', data_consulta + 1) FROM sim_presenca WHERE NOT ouvinte
                        GROUP BY 1, 2 HAVING count(*) > 1) x
  UNION ALL
  SELECT 6, 'Uma linha de presença por pessoa e data',
         count(*) FROM (SELECT assistido_id, data_consulta FROM sim_presenca GROUP BY 1, 2 HAVING count(*) > 1) x
  UNION ALL
  SELECT 7, 'Presença efetiva no dia de assistência da pessoa',
         count(*) FROM sim_presenca t JOIN sim_dia_em d ON d.presenca_id = t.id
         WHERE NOT t.ouvinte AND (d.dia IS NULL OR extract(dow FROM t.data_consulta) <> CASE d.dia WHEN 'DOMINGO_08H' THEN 0 ELSE 2 END)
  UNION ALL
  SELECT 8, 'Número da série: 1 abre ciclo, os outros seguem a anterior, nunca passa de 4',
         count(*) FROM sim_efetiva WHERE numero_serie NOT BETWEEN 1 AND 4
            OR (numero_serie > 1 AND numero_serie <> coalesce(numero_anterior, 0) + 1)
  UNION ALL
  SELECT 9, 'Regra dos 21 dias dentro do ciclo (semana cancelada do dia soma 7)',
         count(*) FROM sim_efetiva e
         WHERE e.numero_serie > 1 AND e.data_consulta - e.anterior >= 21 + 7 * (
               SELECT count(*) FROM sim_sessao s WHERE s.cancelada_em IS NOT NULL
                 AND s.data > e.anterior AND s.data < e.data_consulta
                 AND extract(dow FROM s.data) = extract(dow FROM e.data_consulta))
  UNION ALL
  SELECT 10, 'Ciclo com 4 efetivas só segue depois de Avaliação + Entrevista',
         count(*) FROM sim_efetiva e
         WHERE e.numero_anterior = 4
           AND NOT EXISTS (SELECT 1 FROM entrevista x WHERE x.assistido_id = e.assistido_id AND x.data = e.data_consulta)
           AND NOT EXISTS (SELECT 1 FROM cartao_encerrado c WHERE c.assistido_id = e.assistido_id AND c.encerrado_em = e.data_consulta)
           -- depois da alta, o P2 novo da recepção abre o ciclo sem entrevista (registrarSessaoAposAlta)
           AND NOT EXISTS (SELECT 1 FROM entrevista x WHERE x.assistido_id = e.assistido_id AND x.resultado = 'ALTA'
                           AND x.data > e.anterior AND x.data <= e.data_consulta)
  UNION ALL
  SELECT 11, 'Avaliação só depois da 4ª presença do cartão',
         count(*) FROM avaliacao a JOIN sim_pessoa p ON p.id = a.assistido_id
         -- (a 1ª sessão criada pela entrevista do mesmo dia não conta: ela vem depois da avaliação)
         WHERE (SELECT e.numero_serie FROM sim_efetiva e WHERE e.assistido_id = a.assistido_id
                  AND (e.data_consulta < a.data OR (e.data_consulta = a.data AND e.numero_serie = 4))
                ORDER BY e.data_consulta DESC LIMIT 1) IS DISTINCT FROM 4
  UNION ALL
  SELECT 12, 'VEZ da avaliação conta todas as da pessoa (1, 2, 3...) e evolução a partir da 2ª',
         count(*) FROM (SELECT a.*, row_number() OVER (PARTITION BY a.assistido_id ORDER BY a.data, a.id) AS n
                        FROM avaliacao a JOIN sim_pessoa p ON p.id = a.assistido_id) x
         WHERE numero_vez <> n OR (n = 1 AND evolucao IS NOT NULL) OR (n > 1 AND evolucao IS NULL)
  UNION ALL
  SELECT 13, 'Ninguém avalia nem entrevista a si mesmo',
         (SELECT count(*) FROM avaliacao a JOIN sim_pessoa p ON p.id = a.assistido_id WHERE a.avaliador_id = a.assistido_id)
       + (SELECT count(*) FROM entrevista x JOIN sim_pessoa p ON p.id = x.assistido_id WHERE x.entrevistador_id = x.assistido_id)
  UNION ALL
  SELECT 14, 'Entrevista no dia da pessoa, depois da avaliação, e abre a 1ª sessão do novo ciclo',
         count(*) FROM entrevista x JOIN sim_pessoa p ON p.id = x.assistido_id JOIN avaliacao a ON a.id = x.avaliacao_id
         WHERE x.data < a.data
            OR extract(dow FROM x.data) NOT IN (0, 2)
            OR (x.resultado = 'NOVO_TRATAMENTO' AND NOT EXISTS (SELECT 1 FROM sim_presenca t WHERE t.assistido_id = x.assistido_id
                    AND t.data_consulta = x.data AND NOT t.ouvinte AND t.numero_serie = 1))
            OR (x.resultado = 'ALTA') <> (x.tratamento_indicado_id IS NULL)
  UNION ALL
  SELECT 15, 'Status do cartão coerente com as presenças e atendimentos',
         count(*) FROM sim_pessoa p
         LEFT JOIN LATERAL (SELECT count(*) AS n FROM sim_presenca t WHERE t.assistido_id = p.id AND NOT t.ouvinte
                            AND t.data_consulta >= p.ciclo_iniciado_em) c ON true
         LEFT JOIN LATERAL (SELECT a.id, a.entrevista_ok FROM (SELECT a.id, a.data, EXISTS (SELECT 1 FROM entrevista x WHERE x.avaliacao_id = a.id) AS entrevista_ok
                            FROM avaliacao a WHERE a.assistido_id = p.id) a ORDER BY a.data DESC, a.id DESC LIMIT 1) u ON true
         WHERE CASE p.status_cartao
                 WHEN 'EM_TRATAMENTO' THEN NOT (c.n BETWEEN 1 AND 3 AND p.tratamento_atual_id IS NOT NULL)
                 WHEN 'AGUARDANDO_AVALIACAO' THEN NOT (c.n = 4 AND (u.id IS NULL OR u.entrevista_ok))
                 WHEN 'AGUARDANDO_ENTREVISTA' THEN NOT (c.n = 4 AND u.id IS NOT NULL AND NOT u.entrevista_ok)
                 WHEN 'INCOMPLETO_POR_TEMPO' THEN false
                 WHEN 'ALTA' THEN NOT (p.tratamento_atual_id IS NULL AND p.ciclo_iniciado_em IS NULL)
                 ELSE true END
  UNION ALL
  SELECT 16, 'Cartão encerrado conta as efetivas do período',
         count(*) FROM cartao_encerrado c JOIN sim_pessoa p ON p.id = c.assistido_id
         WHERE c.sessoes_efetivas <> (SELECT count(*) FROM sim_presenca t WHERE t.assistido_id = c.assistido_id AND NOT t.ouvinte
                                      AND t.data_consulta >= c.iniciado_em AND t.data_consulta < c.encerrado_em)
  UNION ALL
  SELECT 17, 'Preleção: um preletor só, expositor da casa ou convidado',
         count(*) FROM prelecao pr JOIN simulacao_registro r ON r.tabela = 'prelecao' AND r.registro_id = pr.id
         LEFT JOIN trabalhador_funcao f ON f.trabalhador_id = pr.trabalhador_id AND f.funcao = 'EXPOSITOR_PRELETOR'
         LEFT JOIN assistido c ON c.id = pr.convidado_id
         WHERE (pr.trabalhador_id IS NOT NULL AND f.funcao IS NULL) OR (pr.convidado_id IS NOT NULL AND c.vinculo <> 'CONVIDADO')
) v ORDER BY ordem;

-- Retrato do fim do período (o que as telas mostram hoje).
SELECT CASE WHEN p.vinculo = 'TRABALHADOR' THEN 'Trabalhador' ELSE 'Assistido' END AS quem,
       p.status_cartao, count(*) AS pessoas
FROM sim_pessoa p GROUP BY 1, 2 ORDER BY 1, 2;

SELECT coalesce(t.codigo, '(sem tratamento)') AS tratamento_atual, count(*) AS pessoas
FROM sim_pessoa p LEFT JOIN tipo_tratamento t ON t.id = p.tratamento_atual_id
WHERE p.ativo GROUP BY 1 ORDER BY 2 DESC;

SELECT status_final, count(*) AS cartoes FROM cartao_encerrado c JOIN sim_pessoa p ON p.id = c.assistido_id
GROUP BY 1 ORDER BY 2 DESC;
