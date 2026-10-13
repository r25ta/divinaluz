package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.config.AuditoriaListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Consulta da auditoria (2026-10-12) — quem grava é o {@link AuditoriaListener}. Lê por SQL, e não
 * por entidade, porque uma entidade da auditoria seria ela mesma auditada.
 */
@Service
public class AuditoriaService {

    public static final int POR_PAGINA = 50;

    /** Nome de cada entidade na tela, na linguagem da casa. */
    private static final Map<String, String> ENTIDADES = new LinkedHashMap<>();
    static {
        ENTIDADES.put("Assistido", "Cadastro");
        ENTIDADES.put("SessaoTratamento", "Presença");
        ENTIDADES.put("Avaliacao", "Avaliação");
        ENTIDADES.put("Entrevista", "Entrevista");
        ENTIDADES.put("CartaoEncerrado", "Tratamento encerrado");
        ENTIDADES.put("HistoricoDiaFrequencia", "Dia de assistência");
        ENTIDADES.put("Trabalhador", "Perfis de trabalho");
        ENTIDADES.put("SessaoAssistencia", "Sessão");
        ENTIDADES.put("EscalaSessao", "Escala da sessão");
        ENTIDADES.put("Prelecao", "Preleção");
        ENTIDADES.put("CelularVinculado", "Celular vinculado");
        ENTIDADES.put("TipoTratamento", "Catálogo de tratamentos");
    }

    private final JdbcTemplate jdbc;

    public AuditoriaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Filtro(LocalDate de, LocalDate ate, String usuario, String acao, String entidade,
            String busca, Long assistidoId) {}

    public record Campo(String campo, String valorAnterior, String valorNovo) {
        public String getRotulo() {
            return rotuloCampo(campo);
        }
    }

    public record Evento(long id, LocalDateTime dataHora, String usuarioLogin, String usuarioNome, String acao,
            String entidade, String registroId, String registroDescricao, Long assistidoId, String origem,
            List<Campo> campos) {
        public String getEntidadeRotulo() {
            return rotuloEntidade(entidade);
        }

        public String getAcaoRotulo() {
            return switch (acao) {
                case AuditoriaListener.INCLUSAO -> "Inclusão";
                case AuditoriaListener.EXCLUSAO -> "Exclusão";
                default -> "Alteração";
            };
        }
    }

    public record Pagina(List<Evento> eventos, long total, int pagina, int totalPaginas) {}

    public Pagina listar(Filtro filtro, int pagina) {
        List<Object> parametros = new ArrayList<>();
        String where = montarWhere(filtro, parametros);

        Long total = jdbc.queryForObject("SELECT count(*) FROM auditoria a" + where, Long.class, parametros.toArray());
        long totalLinhas = total == null ? 0 : total;
        int totalPaginas = (int) Math.max(1, (totalLinhas + POR_PAGINA - 1) / POR_PAGINA);
        int atual = Math.min(Math.max(pagina, 1), totalPaginas);

        List<Object> parametrosPagina = new ArrayList<>(parametros);
        parametrosPagina.add(POR_PAGINA);
        parametrosPagina.add((atual - 1) * POR_PAGINA);
        // O nome de quem agiu vem do cadastro pelo login (o login é único sem diferenciar maiúsculas).
        List<Evento> semCampos = jdbc.query("""
                SELECT a.*, u.nome AS usuario_nome
                  FROM auditoria a
                  LEFT JOIN assistido u ON lower(u.login) = lower(a.usuario_login)
                """ + where + " ORDER BY a.data_hora DESC, a.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new Evento(
                        rs.getLong("id"),
                        rs.getTimestamp("data_hora").toLocalDateTime(),
                        rs.getString("usuario_login"),
                        rs.getString("usuario_nome"),
                        rs.getString("acao"),
                        rs.getString("entidade"),
                        rs.getString("registro_id"),
                        rs.getString("registro_descricao"),
                        (Long) rs.getObject("assistido_id", Long.class),
                        rs.getString("origem"),
                        List.of()),
                parametrosPagina.toArray());

        return new Pagina(comCampos(semCampos), totalLinhas, atual, totalPaginas);
    }

    private List<Evento> comCampos(List<Evento> eventos) {
        if (eventos.isEmpty()) {
            return eventos;
        }
        String ids = eventos.stream().map(e -> String.valueOf(e.id())).collect(Collectors.joining(","));
        Map<Long, List<Campo>> porEvento = new LinkedHashMap<>();
        jdbc.query("SELECT auditoria_id, campo, valor_anterior, valor_novo FROM auditoria_campo "
                        + "WHERE auditoria_id IN (" + ids + ") ORDER BY id",
                rs -> {
                    porEvento.computeIfAbsent(rs.getLong("auditoria_id"), k -> new ArrayList<>())
                            .add(new Campo(rs.getString("campo"), rs.getString("valor_anterior"), rs.getString("valor_novo")));
                });
        return eventos.stream()
                .map(e -> new Evento(e.id(), e.dataHora(), e.usuarioLogin(), e.usuarioNome(), e.acao(), e.entidade(),
                        e.registroId(), e.registroDescricao(), e.assistidoId(), e.origem(),
                        porEvento.getOrDefault(e.id(), List.of())))
                .toList();
    }

    private static String montarWhere(Filtro f, List<Object> parametros) {
        List<String> condicoes = new ArrayList<>();
        if (f.de() != null) {
            condicoes.add("a.data_hora >= ?");
            parametros.add(Timestamp.valueOf(f.de().atStartOfDay()));
        }
        if (f.ate() != null) {
            condicoes.add("a.data_hora < ?");
            parametros.add(Timestamp.valueOf(f.ate().plusDays(1).atStartOfDay()));
        }
        if (temTexto(f.usuario())) {
            condicoes.add("lower(a.usuario_login) LIKE ?");
            parametros.add("%" + f.usuario().trim().toLowerCase() + "%");
        }
        if (temTexto(f.acao())) {
            condicoes.add("a.acao = ?");
            parametros.add(f.acao().trim());
        }
        if (temTexto(f.entidade())) {
            condicoes.add("a.entidade = ?");
            parametros.add(f.entidade().trim());
        }
        if (temTexto(f.busca())) {
            condicoes.add("(lower(a.registro_descricao) LIKE ? OR a.registro_id = ?)");
            parametros.add("%" + f.busca().trim().toLowerCase() + "%");
            parametros.add(f.busca().trim());
        }
        if (f.assistidoId() != null) {
            condicoes.add("a.assistido_id = ?");
            parametros.add(f.assistidoId());
        }
        return condicoes.isEmpty() ? "" : " WHERE " + String.join(" AND ", condicoes);
    }

    /** Limites do aviso de espaço — os mesmos do scripts/backup-nuvem.ps1. O Neon gratuito tem 512 MB. */
    public static final double LIMITE_PLANO_MB = 512;
    public static final double ALERTA_BANCO_MB = 300;
    public static final double ALERTA_AUDITORIA_MB = 100;

    public record Espaco(double bancoMb, double auditoriaMb) {
        public boolean isAlerta() {
            return bancoMb >= ALERTA_BANCO_MB || auditoriaMb >= ALERTA_AUDITORIA_MB;
        }

        public long getPercentualDoPlano() {
            return Math.round(bancoMb * 100 / LIMITE_PLANO_MB);
        }
    }

    /** Quanto o banco e a auditoria ocupam (2026-10-12): a auditoria não apaga nada. */
    public Espaco espaco() {
        return jdbc.queryForObject("""
                SELECT pg_database_size(current_database()) / 1048576.0 AS banco,
                       (pg_total_relation_size('auditoria') + pg_total_relation_size('auditoria_campo')) / 1048576.0 AS auditoria
                """, (rs, i) -> new Espaco(rs.getDouble("banco"), rs.getDouble("auditoria")));
    }

    /** As entidades que já aparecem na auditoria, para o filtro (código → nome na tela). */
    public Map<String, String> entidades() {
        Map<String, String> todas = new LinkedHashMap<>();
        for (String entidade : jdbc.queryForList("SELECT DISTINCT entidade FROM auditoria", String.class)) {
            todas.put(entidade, rotuloEntidade(entidade));
        }
        return todas.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    public static String rotuloEntidade(String entidade) {
        return ENTIDADES.getOrDefault(entidade, entidade);
    }

    /** "dataNascimento" → "Data nascimento": o nome do campo no código, legível na tela. */
    public static String rotuloCampo(String campo) {
        if (campo == null || campo.isEmpty()) {
            return campo;
        }
        String separado = campo.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(separado.charAt(0)) + separado.substring(1);
    }

    private static boolean temTexto(String texto) {
        return texto != null && !texto.isBlank();
    }
}
