-- P3B existia só como posição da escala da sessão (PosicaoSessao.P3B, mínimo de 3 passistas), mas
-- não estava no catálogo de tratamentos da V3 — então nenhum assistido podia ser posto em P3B.
-- Descrição informada pelo responsável do projeto em 2026-09-28.
-- "objetivo_indicacao" e "triagem_observacoes" ficam nulos por ora (não informados); como não há
-- tela de cadastro de TipoTratamento, completá-los depois exige uma migration nova.
INSERT INTO tipo_tratamento (codigo, nome, como_funciona, objetivo_indicacao, triagem_observacoes)
VALUES ('P3B', 'Atendimento Espiritual Individualizado',
        'Atendimento espiritual individualizado para ajudar uma pessoa e um espírito ligado à história dela.',
        NULL, NULL);
