package br.com.nae.divinaluz.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseUrlConfigTest {

    // Exatamente o formato que o botão "Connect" do Neon entrega.
    @Test
    void converteAConnectionStringDoNeon() {
        DatabaseUrlConfig.Conexao c = DatabaseUrlConfig.converter(
                "postgresql://neondb_owner:npg_AbC123@ep-cool-sun-a1b2c3.sa-east-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");

        assertEquals("jdbc:postgresql://ep-cool-sun-a1b2c3.sa-east-1.aws.neon.tech/neondb?sslmode=require", c.jdbcUrl());
        assertEquals("neondb_owner", c.usuario());
        assertEquals("npg_AbC123", c.senha());
    }

    @Test
    void aceitaPortaPrefixoPostgresEJdbcESenhaCodificada() {
        DatabaseUrlConfig.Conexao c = DatabaseUrlConfig.converter("postgres://app:s%40nh%3A@db.local:6543/demo");

        assertEquals("jdbc:postgresql://db.local:6543/demo", c.jdbcUrl());
        assertEquals("s@nh:", c.senha());

        assertEquals("jdbc:postgresql://h/b?sslmode=require",
                DatabaseUrlConfig.converter("jdbc:postgresql://u:p@h/b?sslmode=require").jdbcUrl());
    }

    @Test
    void recusaEnderecoSemUsuarioOuForaDoFormato() {
        assertThrows(IllegalStateException.class, () -> DatabaseUrlConfig.converter("postgresql://host/neondb"));
        assertThrows(IllegalStateException.class, () -> DatabaseUrlConfig.converter("mysql://u:p@host/db"));
    }
}
