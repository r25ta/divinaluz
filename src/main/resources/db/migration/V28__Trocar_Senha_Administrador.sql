-- Troca a credencial provisória do admin (admin/password, ver V19-V23) por uma senha real
-- antes de qualquer uso em produção. V19-V23 já rodaram e não podem ser editadas, então a
-- troca precisa vir como migration nova (item 1 dos Próximos Passos do CLAUDE.md).
-- Desde a V25, os dados de acesso vivem em "assistido" (não mais em "usuario").
UPDATE assistido
SET senha = '$2a$10$LlI5TtJj4mq.3Rw7Yk.owewhCeFEOlWrRWHsZtOLnLeOc.2ElAe8u'
WHERE login = 'admin';
