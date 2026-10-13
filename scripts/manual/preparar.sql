-- Logins de demonstração para fotografar as telas do manual (só no banco descartável do CI, sobre a
-- carga de simulação). Senha de todos: manual123.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TEMP TABLE escolhido (login text, assistido_id bigint);

-- Uma pessoa diferente para cada função, e com essa função SÓ: a tela mostra exatamente o que aquela
-- função vê. A simulação quase não tem Dirigente, Avaliador e Expositor/Preletor de função única (o
-- gerador os faz acumular), então escolhe quem tem menos funções, de preferência Em Tratamento, e tira as
-- outras funções dela — o banco é descartável. A primeira versão exigia função única e deixava essas três
-- sem login, e as telas delas saíam como a tela de login.
DO $$
DECLARE
    funcao_alvo text;
    trab_id bigint;
BEGIN
    FOREACH funcao_alvo IN ARRAY ARRAY['DIRIGENTE', 'AVALIADOR', 'EXPOSITOR_PRELETOR',
                                       'ENTREVISTADOR', 'RECEPCIONISTA', 'PASSISTA'] LOOP
        SELECT t.id INTO trab_id
        FROM trabalhador t
        JOIN trabalhador_funcao f ON f.trabalhador_id = t.id AND f.funcao = funcao_alvo
        JOIN assistido a ON a.id = t.assistido_id AND a.ativo
        WHERE t.assistido_id NOT IN (SELECT assistido_id FROM escolhido)
        ORDER BY (SELECT count(*) FROM trabalhador_funcao g WHERE g.trabalhador_id = t.id),
                 (a.status_cartao <> 'EM_TRATAMENTO'), t.id
        LIMIT 1;
        IF trab_id IS NULL THEN
            RAISE EXCEPTION 'Nenhum trabalhador com a função % na simulação', funcao_alvo;
        END IF;
        DELETE FROM trabalhador_funcao WHERE trabalhador_id = trab_id AND funcao <> funcao_alvo;
        INSERT INTO escolhido SELECT lower(funcao_alvo), assistido_id FROM trabalhador WHERE id = trab_id;
    END LOOP;
END $$;

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
