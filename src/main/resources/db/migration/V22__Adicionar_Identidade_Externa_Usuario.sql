ALTER TABLE usuario
    ADD COLUMN email VARCHAR(255),
    ADD COLUMN provedor VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN identificador_externo VARCHAR(255);

CREATE UNIQUE INDEX uk_usuario_provedor_externo
    ON usuario (provedor, identificador_externo)
    WHERE identificador_externo IS NOT NULL;