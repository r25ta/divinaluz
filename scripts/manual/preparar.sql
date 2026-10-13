-- Logins de demonstração para fotografar as telas do manual (só no banco descartável do CI, sobre a
-- carga de simulação). Senha de todos: manual123.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TEMP TABLE escolhido (login text, assistido_id bigint);

-- Um trabalhador com UMA função só, para cada função: a tela mostra exatamente o que aquela função vê.
INSERT INTO escolhido
SELECT lower(f.funcao) AS login, min(t.assistido_id)
FROM trabalhador t
JOIN trabalhador_funcao f ON f.trabalhador_id = t.id
JOIN assistido a ON a.id = t.assistido_id AND a.ativo
WHERE t.id IN (SELECT trabalhador_id FROM trabalhador_funcao GROUP BY trabalhador_id HAVING count(*) = 1)
  AND a.status_cartao = 'EM_TRATAMENTO'
GROUP BY f.funcao;

-- Um assistido de terça, em tratamento, com presença no ciclo (o cartão aparece com marcações).
INSERT INTO escolhido
SELECT 'assistido', a.id
FROM assistido a
WHERE a.vinculo = 'ASSISTIDO' AND a.ativo AND a.status_cartao = 'EM_TRATAMENTO' AND a.dia_frequencia = 'TERCA_19H'
  AND (SELECT count(*) FROM sessao_tratamento s WHERE s.assistido_id = a.id AND NOT s.ouvinte
       AND s.data_consulta >= a.ciclo_iniciado_em) BETWEEN 2 AND 3
ORDER BY a.id LIMIT 1;

UPDATE assistido a
SET login = e.login,
    senha = crypt('manual123', gen_salt('bf', 10)),
    perfil_acesso = CASE WHEN e.login = 'assistido' THEN 'ASSISTIDO' ELSE 'TRABALHADOR' END,
    acesso_ativo = true
FROM escolhido e
WHERE a.id = e.assistido_id;

UPDATE assistido SET senha = crypt('manual123', gen_salt('bf', 10)), acesso_ativo = true WHERE login = 'admin';

SELECT login, assistido_id, (SELECT nome FROM assistido WHERE id = assistido_id) FROM escolhido ORDER BY login;
