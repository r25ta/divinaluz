-- Cadastro de assistido passa a criar o acesso automaticamente (perfil ASSISTIDO). Quando há
-- e-mail, o assistido recebe um link para definir a própria senha (ver AcessoService/EmailService);
-- esse token fica aqui até ser usado (ou expirar) e a senha continuar null até lá.
ALTER TABLE assistido
    ADD COLUMN token_definicao_senha VARCHAR(64) UNIQUE,
    ADD COLUMN token_definicao_senha_expira_em TIMESTAMP;
