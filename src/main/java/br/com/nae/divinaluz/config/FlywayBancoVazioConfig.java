package br.com.nae.divinaluz.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Permite subir a aplicação num PostgreSQL vazio (nuvem da demo, CI, máquina nova). As migrations
 * V1 e V2 criam a mesma tabela avaliacao (ver CLAUDE.md seção 6, "Banco zerado"), então, só quando
 * o schema não tem nenhuma tabela, as tabelas da V1 são criadas antes do Flyway: com o schema não
 * vazio ele baselineia na V1 e roda da V2 em diante. Em qualquer banco já existente (o de dev,
 * produção) isto não faz nada.
 */
@Configuration
public class FlywayBancoVazioConfig {

    private static final String SCRIPT_V1 = "db/bootstrap/tabelas-iniciais-v1.sql";

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(DataSource dataSource) {
        return flyway -> {
            try (Connection conexao = dataSource.getConnection()) {
                if (schemaVazio(conexao)) {
                    ScriptUtils.executeSqlScript(conexao, new ClassPathResource(SCRIPT_V1));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Não foi possível preparar o banco vazio para o Flyway.", e);
            }
            flyway.migrate();
        };
    }

    private boolean schemaVazio(Connection conexao) throws SQLException {
        try (Statement st = conexao.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()")) {
            rs.next();
            return rs.getLong(1) == 0;
        }
    }
}
