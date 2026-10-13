package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.EscalaSessao;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.model.Trabalhador;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.Session;
import org.hibernate.SharedSessionContract;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.type.Type;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Módulo de Auditoria (2026-10-12): toda inclusão, alteração e exclusão de entidade gravada pelo
 * sistema vira uma linha em {@code auditoria} (quem, o quê, quando, de onde) com os campos em
 * {@code auditoria_campo} (valor anterior e novo). Consulta só do Administrador ({@code /auditoria}).
 *
 * <p>Escuta os eventos do Hibernate em vez de chamadas espalhadas pelos serviços: assim nenhuma ação
 * nova escapa por esquecimento. Grava pela <strong>mesma conexão</strong> da sessão, dentro da
 * transação da ação — se ela é desfeita, o registro dela também, e o que sobrevive a um
 * {@code noRollbackFor} (o cartão expirado) também é auditado.</p>
 *
 * <p>Não pega: as coleções ({@code Trabalhador.funcoes} é registrada à mão em
 * {@code TrabalhadorService.definirPerfis}), o que é gravado por SQL direto (a tabela do "lembrar deste
 * aparelho", as migrations e os scripts) e o lado inverso dos relacionamentos.</p>
 *
 * <p>{@code @Lazy(false)} porque o perfil {@code prod} usa {@code lazy-initialization}: preguiçoso,
 * este bean nunca seria criado e nada seria auditado.</p>
 */
@Component
@Lazy(false)
public class AuditoriaListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    public static final String INCLUSAO = "INCLUSAO";
    public static final String ALTERACAO = "ALTERACAO";
    public static final String EXCLUSAO = "EXCLUSAO";

    /** Quem age sem login (presença pelo celular vinculado, entrada por código) e o próprio sistema. */
    public static final String SEM_LOGIN = "(sem login)";
    public static final String SISTEMA = "(sistema)";

    /** Hashes, senhas e códigos: a auditoria diz que mudaram, nunca o valor. */
    private static final Set<String> CAMPOS_OCULTOS = Set.of("senha", "codigoAcesso", "tokenDefinicaoSenha",
            "conviteCelularHash", "checkinSegredo", "tokenHash", "codigoCartao");
    private static final String OCULTO = "(oculto)";

    private static final int MAXIMO_VALOR = 4000;
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final EntityManagerFactory entityManagerFactory;

    public AuditoriaListener(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @PostConstruct
    void registrar() {
        EventListenerRegistry registro = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().requireService(EventListenerRegistry.class);
        registro.appendListeners(EventType.POST_INSERT, this);
        registro.appendListeners(EventType.POST_UPDATE, this);
        registro.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        List<Campo> campos = new ArrayList<>();
        EntityPersister persister = event.getPersister();
        String[] nomes = persister.getPropertyNames();
        Type[] tipos = persister.getPropertyTypes();
        for (int i = 0; i < nomes.length; i++) {
            if (tipos[i].isCollectionType()) {
                continue;
            }
            String novo = valor(nomes[i], event.getState()[i]);
            if (novo != null) {
                campos.add(new Campo(nomes[i], null, novo));
            }
        }
        gravar(event.getSession(), INCLUSAO, event.getEntity(), event.getId(), campos);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        Object[] antes = event.getOldState();
        Object[] depois = event.getState();
        EntityPersister persister = event.getPersister();
        String[] nomes = persister.getPropertyNames();
        Type[] tipos = persister.getPropertyTypes();

        // Sem o estado anterior (raro: entidade atualizada sem ter sido lida), fica-se com os campos
        // que o Hibernate marcou como alterados.
        Set<Integer> sujos = new java.util.HashSet<>();
        if (antes == null && event.getDirtyProperties() != null) {
            for (int indice : event.getDirtyProperties()) {
                sujos.add(indice);
            }
        }

        List<Campo> campos = new ArrayList<>();
        for (int i = 0; i < nomes.length; i++) {
            if (tipos[i].isCollectionType() || (antes == null && !sujos.isEmpty() && !sujos.contains(i))) {
                continue;
            }
            Object anteriorBruto = antes == null ? null : antes[i];
            if (antes != null && Objects.equals(anteriorBruto, depois[i])) {
                continue;
            }
            String anterior = antes == null ? "(desconhecido)" : valor(nomes[i], anteriorBruto);
            String novo = valor(nomes[i], depois[i]);
            if (CAMPOS_OCULTOS.contains(nomes[i]) && antes != null) {
                // Mesmo oculto, quem lê precisa saber que mudou (ex.: a senha foi trocada).
                anterior = anteriorBruto == null ? null : OCULTO;
                novo = depois[i] == null ? null : OCULTO + " alterado";
            }
            if (!Objects.equals(anterior, novo)) {
                campos.add(new Campo(nomes[i], anterior, novo));
            }
        }
        if (!campos.isEmpty()) {
            gravar(event.getSession(), ALTERACAO, event.getEntity(), event.getId(), campos);
        }
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        List<Campo> campos = new ArrayList<>();
        EntityPersister persister = event.getPersister();
        String[] nomes = persister.getPropertyNames();
        Type[] tipos = persister.getPropertyTypes();
        Object[] estado = event.getDeletedState();
        for (int i = 0; estado != null && i < nomes.length; i++) {
            if (tipos[i].isCollectionType()) {
                continue;
            }
            String anterior = valor(nomes[i], estado[i]);
            if (anterior != null) {
                campos.add(new Campo(nomes[i], anterior, null));
            }
        }
        gravar(event.getSession(), EXCLUSAO, event.getEntity(), event.getId(), campos);
    }

    /**
     * Para o que os eventos não pegam (as coleções): uma alteração de um campo, registrada à mão por
     * quem a faz, na mesma sessão e transação.
     */
    public static void registrarAlteracao(Session sessao, Object entidade, Object id, String campo,
            String anterior, String novo) {
        if (!Objects.equals(anterior, novo)) {
            gravar(sessao, ALTERACAO, entidade, id, List.of(new Campo(campo, anterior, novo)));
        }
    }

    private record Campo(String nome, String anterior, String novo) {}

    private static void gravar(SharedSessionContract sessao, String acao, Object entidade, Object id, List<Campo> campos) {
        Object real = desembrulhar(entidade);
        String nomeEntidade = real.getClass().getSimpleName();
        String usuario = usuarioAtual();
        String origem = origemAtual();
        String descricao = cortar(descrever(real), 200);
        Long assistidoId = assistidoDe(real);

        sessao.doWork(conexao -> {
            long auditoriaId = inserirCabecalho(conexao, acao, nomeEntidade, id, descricao, assistidoId, usuario, origem);
            inserirCampos(conexao, auditoriaId, campos);
        });
    }

    private static long inserirCabecalho(Connection conexao, String acao, String entidade, Object id,
            String descricao, Long assistidoId, String usuario, String origem) throws SQLException {
        String sql = "INSERT INTO auditoria (data_hora, usuario_login, acao, entidade, registro_id, "
                + "registro_descricao, assistido_id, origem) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (PreparedStatement ps = conexao.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(2, usuario);
            ps.setString(3, acao);
            ps.setString(4, entidade);
            ps.setString(5, id == null ? null : cortar(String.valueOf(id), 40));
            ps.setString(6, descricao);
            if (assistidoId == null) {
                ps.setNull(7, java.sql.Types.BIGINT);
            } else {
                ps.setLong(7, assistidoId);
            }
            ps.setString(8, cortar(origem, 250));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void inserirCampos(Connection conexao, long auditoriaId, List<Campo> campos) throws SQLException {
        if (campos.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO auditoria_campo (auditoria_id, campo, valor_anterior, valor_novo) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conexao.prepareStatement(sql)) {
            for (Campo campo : campos) {
                ps.setLong(1, auditoriaId);
                ps.setString(2, cortar(campo.nome(), 80));
                ps.setString(3, campo.anterior());
                ps.setString(4, campo.novo());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---- quem, de onde -------------------------------------------------------------------------

    private static String usuarioAtual() {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null) {
            // Sem contexto de segurança: tarefa do próprio sistema (encerramento automático, subida).
            return RequestContextHolder.getRequestAttributes() == null ? SISTEMA : SEM_LOGIN;
        }
        if (autenticacao instanceof AnonymousAuthenticationToken || !autenticacao.isAuthenticated()) {
            return SEM_LOGIN;
        }
        return cortar(autenticacao.getName(), 120);
    }

    private static String origemAtual() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes atributos) {
            HttpServletRequest request = atributos.getRequest();
            String caminho = request.getRequestURI().substring(request.getContextPath().length());
            return request.getMethod() + " " + caminho;
        }
        return "sistema";
    }

    // ---- como cada coisa aparece ---------------------------------------------------------------

    private static String valor(String campo, Object bruto) {
        if (bruto == null) {
            return null;
        }
        if (CAMPOS_OCULTOS.contains(campo)) {
            return OCULTO;
        }
        return cortar(formatar(bruto), MAXIMO_VALOR);
    }

    static String formatar(Object bruto) {
        if (bruto == null) {
            return null;
        }
        if (bruto instanceof HibernateProxy proxy) {
            LazyInitializer li = proxy.getHibernateLazyInitializer();
            if (li.isUninitialized()) {
                String entidade = li.getEntityName();
                return entidade.substring(entidade.lastIndexOf('.') + 1) + " #" + li.getIdentifier();
            }
            bruto = li.getImplementation();
        }
        if (bruto instanceof Boolean b) {
            return b ? "Sim" : "Não";
        }
        if (bruto instanceof LocalDate d) {
            return d.format(DATA);
        }
        if (bruto instanceof LocalDateTime dt) {
            return dt.format(DATA_HORA);
        }
        if (bruto instanceof LocalTime t) {
            return t.toString();
        }
        if (bruto instanceof Enum<?> e) {
            Object label = chamar(e, "getLabel");
            return label != null ? label.toString() : e.name();
        }
        if (bruto instanceof Collection<?> || bruto instanceof Map<?, ?>) {
            return String.valueOf(bruto);
        }
        if (bruto.getClass().isAnnotationPresent(Entity.class)) {
            return descreverReferencia(bruto);
        }
        return bruto.toString();
    }

    /** Uma entidade apontada por outra (o assistido de uma avaliação, o tratamento indicado...). */
    private static String descreverReferencia(Object entidade) {
        if (entidade instanceof TipoTratamento t) {
            return t.getCodigo();
        }
        String descricao = descrever(entidade);
        Object id = chamar(entidade, "getId");
        return descricao != null ? descricao + " (#" + id + ")" : entidade.getClass().getSimpleName() + " #" + id;
    }

    /** O nome que identifica o registro na tela da auditoria. */
    private static String descrever(Object entidade) {
        if (entidade instanceof Assistido a) {
            return a.getNome();
        }
        if (entidade instanceof Trabalhador t) {
            return nomeDoAssistido(t.getAssistido());
        }
        if (entidade instanceof SessaoAssistencia s) {
            return "Sessão de " + (s.getData() == null ? "?" : s.getData().format(DATA));
        }
        if (entidade instanceof Prelecao p) {
            return (p.getDataApresentacao() == null ? "" : p.getDataApresentacao().format(DATA) + " — ") + p.getTema();
        }
        if (entidade instanceof EscalaSessao e) {
            Trabalhador t = carregado(e.getTrabalhador()) instanceof Trabalhador tr ? tr : null;
            return (t == null ? "" : nomeDoAssistido(t.getAssistido()) + " — ") + formatar(e.getPosicao());
        }
        if (entidade instanceof TipoTratamento t) {
            return t.getCodigo();
        }
        Object assistido = chamar(entidade, "getAssistido");
        return assistido == null ? null : nomeDoAssistido(assistido);
    }

    private static Long assistidoDe(Object entidade) {
        if (entidade instanceof Assistido a) {
            return a.getId();
        }
        Object assistido = chamar(entidade, "getAssistido");
        if (assistido instanceof HibernateProxy proxy) {
            return (Long) proxy.getHibernateLazyInitializer().getIdentifier();
        }
        return assistido instanceof Assistido a ? a.getId() : null;
    }

    private static String nomeDoAssistido(Object assistido) {
        Object real = carregado(assistido);
        if (real instanceof Assistido a) {
            return a.getNome();
        }
        if (assistido instanceof HibernateProxy proxy) {
            return "Assistido #" + proxy.getHibernateLazyInitializer().getIdentifier();
        }
        return null;
    }

    /** A entidade de verdade, sem carregar do banco um proxy que ainda não foi lido. */
    private static Object carregado(Object talvezProxy) {
        if (talvezProxy instanceof HibernateProxy proxy) {
            LazyInitializer li = proxy.getHibernateLazyInitializer();
            return li.isUninitialized() ? null : li.getImplementation();
        }
        return talvezProxy;
    }

    private static Object desembrulhar(Object entidade) {
        if (entidade instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getImplementation();
        }
        return entidade;
    }

    private static Object chamar(Object alvo, String metodo) {
        try {
            Method m = alvo.getClass().getMethod(metodo);
            return m.invoke(alvo);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String cortar(String texto, int maximo) {
        if (texto == null || texto.length() <= maximo) {
            return texto;
        }
        return texto.substring(0, maximo - 1) + "…";
    }
}
