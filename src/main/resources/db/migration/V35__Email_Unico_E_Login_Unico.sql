-- Um e-mail por cadastro e login único sem diferenciar maiúsculas (2026-10-03).
--
-- O e-mail passa a identificar a pessoa: a entrada por código procura por ele, e dois cadastros
-- com o mesmo e-mail costumam ser a mesma pessoa cadastrada duas vezes. Esta migration roda num
-- banco que já tem dados reais (o piloto), então NÃO pode falhar por causa de repetições que já
-- existam — nem pode apagar dado de ninguém. Por isso ela resolve as repetições em vez de recusar:

-- 1) E-mail em branco vira NULL (o formulário gravava "" quando o campo ficava vazio, e "" repetido
--    em vários cadastros violaria a unicidade sem ser e-mail de ninguém).
UPDATE assistido SET email = NULL WHERE email IS NOT NULL AND btrim(email) = '';

-- 2) Mesma grafia para todos: sem espaços nas pontas e em minúsculas.
UPDATE assistido SET email = lower(btrim(email)) WHERE email IS NOT NULL;

-- 3) Repetições: o e-mail fica com quem o usa como login (é a entrada daquela pessoa), depois com o
--    cadastro ativo, depois com o mais antigo. Dos outros, o e-mail sai do campo mas fica guardado
--    em email_conflito, e o prontuário avisa a recepção para resolver (desativar o duplicado ou
--    corrigir o e-mail). Salvar a edição do cadastro limpa o aviso.
ALTER TABLE assistido ADD COLUMN email_conflito VARCHAR(255);

WITH ordenados AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY email
               ORDER BY (lower(login) = email) DESC NULLS LAST, ativo DESC, id
           ) AS posicao
    FROM assistido
    WHERE email IS NOT NULL
)
UPDATE assistido a
SET email_conflito = a.email,
    email = NULL
FROM ordenados o
WHERE a.id = o.id AND o.posicao > 1;

CREATE UNIQUE INDEX uk_assistido_email ON assistido (lower(email));

-- 4) Login único sem diferenciar maiúsculas. O UNIQUE da V25 já impede o login idêntico; este
--    índice impede "Maria" e "maria". Se já existir esse caso no banco (improvável: os logins são
--    gerados em minúsculas), o índice não é criado, para não travar o deploy — a aplicação valida
--    do mesmo jeito antes de gravar.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM assistido WHERE login IS NOT NULL GROUP BY lower(login) HAVING count(*) > 1
    ) THEN
        RAISE WARNING 'Há logins que só diferem por maiúsculas; índice uk_assistido_login_minusculo não criado.';
    ELSE
        CREATE UNIQUE INDEX uk_assistido_login_minusculo ON assistido (lower(login));
    END IF;
END $$;
