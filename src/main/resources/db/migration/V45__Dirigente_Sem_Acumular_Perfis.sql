-- O Dirigente acumula todos os perfis (2026-10-12, decisão do responsável do projeto): quem é
-- Dirigente fica gravado só como Dirigente. Tira as demais funções de quem já as tinha junto.
--
-- Não muda nada no que a pessoa alcança — o Dirigente já tinha todas as permissões —, e a escala de
-- preleções continua aceitando-o como preletor (Trabalhador.exerce). Como é SQL direto, não passa
-- pela auditoria (V44).

DELETE FROM trabalhador_funcao tf
 WHERE tf.funcao <> 'DIRIGENTE'
   AND EXISTS (SELECT 1 FROM trabalhador_funcao d
                WHERE d.trabalhador_id = tf.trabalhador_id
                  AND d.funcao = 'DIRIGENTE');
