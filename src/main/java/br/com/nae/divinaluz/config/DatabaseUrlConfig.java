package br.com.nae.divinaluz.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Aceita o banco no formato que as plataformas de hospedagem mostram (Neon, Render, Railway):
 * {@code DATABASE_URL=postgresql://usuario:senha@host/banco?sslmode=require}. Assim um deploy pede
 * uma única variável, copiada e colada, em vez de separar URL JDBC, usuário e senha à mão. Sem
 * {@code DATABASE_URL} vale o {@code spring.datasource.*} de sempre, que é o caso do dev.
 */
@Configuration
@ConditionalOnProperty(name = "DATABASE_URL")
public class DatabaseUrlConfig {

    /** Parâmetros que o driver JDBC do PostgreSQL entende; os demais (ex.: channel_binding) são descartados. */
    private static final List<String> PARAMETROS_ACEITOS = List.of("sslmode", "sslrootcert", "options");

    record Conexao(String jdbcUrl, String usuario, String senha) {}

    @Bean
    DataSource dataSource(Environment env) {
        Conexao conexao = converter(env.getRequiredProperty("DATABASE_URL"));
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(conexao.jdbcUrl());
        dataSource.setUsername(conexao.usuario());
        dataSource.setPassword(conexao.senha());
        dataSource.setMaximumPoolSize(env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 4));
        return dataSource;
    }

    static Conexao converter(String databaseUrl) {
        String url = databaseUrl.trim();
        if (url.startsWith("jdbc:")) {
            url = url.substring("jdbc:".length());
        }
        if (!url.startsWith("postgres://") && !url.startsWith("postgresql://")) {
            throw new IllegalStateException("DATABASE_URL precisa começar com postgresql:// (copie a connection string do Neon).");
        }
        URI uri = URI.create(url.replaceFirst("^postgres(ql)?://", "http://"));
        String info = uri.getRawUserInfo();
        if (info == null || !info.contains(":")) {
            throw new IllegalStateException("DATABASE_URL sem usuário e senha (formato postgresql://usuario:senha@host/banco).");
        }
        String usuario = decodificar(info.substring(0, info.indexOf(':')));
        String senha = decodificar(info.substring(info.indexOf(':') + 1));

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/postgres" : uri.getRawPath());

        List<String> parametros = new ArrayList<>();
        if (uri.getRawQuery() != null) {
            for (String par : uri.getRawQuery().split("&")) {
                String nome = par.contains("=") ? par.substring(0, par.indexOf('=')) : par;
                if (PARAMETROS_ACEITOS.contains(nome)) {
                    parametros.add(par);
                }
            }
        }
        if (!parametros.isEmpty()) {
            jdbc.append('?').append(String.join("&", parametros));
        }
        return new Conexao(jdbc.toString(), usuario, senha);
    }

    private static String decodificar(String valor) {
        return URLDecoder.decode(valor, StandardCharsets.UTF_8);
    }
}
