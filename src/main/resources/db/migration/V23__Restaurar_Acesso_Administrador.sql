UPDATE usuario
SET senha = '$2a$10$WCkjWVXEhFm6dJiSl4NIYuKwFmywopRdmWC2vwmcg4cuLYddGSB0K',
    ativo = TRUE,
    perfil = 'ADMINISTRADOR'
WHERE login = 'admin';
