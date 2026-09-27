package br.com.nae.divinaluz.demo;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Avaliacao;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.EscalaSessao;
import br.com.nae.divinaluz.model.Evolucao;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.PosicaoSessao;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoAssistenciaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import br.com.nae.divinaluz.service.SessaoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dados fictícios da versão de demonstração (perfil {@code demo}, ver DEPLOY-DEMO.md). Roda a cada
 * subida: a senha do {@code admin} sempre vem de {@code DEMO_ADMIN_SENHA} (a provisória das
 * migrations nunca vale na demo), e o restante só é cadastrado se o banco ainda não tem
 * assistidos — reiniciar a aplicação não duplica nada. As datas são relativas a hoje, para a demo
 * sempre abrir com um histórico recente, a próxima sessão escalada e casos de todos os status.
 * Nenhum nome é de pessoa real.
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);
    static final String LOGIN_ASSISTIDO_DEMO = "assistido";

    private final AssistidoRepository assistidoRepository;
    private final TrabalhadorRepository trabalhadorRepository;
    private final TipoTratamentoRepository tipoTratamentoRepository;
    private final SessaoRepository sessaoRepository;
    private final AvaliacaoRepository avaliacaoRepository;
    private final PrelecaoRepository prelecaoRepository;
    private final SessaoAssistenciaRepository sessaoAssistenciaRepository;
    private final PasswordEncoder passwordEncoder;
    private final String senhaAdmin;

    public DemoDataLoader(AssistidoRepository assistidoRepository, TrabalhadorRepository trabalhadorRepository,
            TipoTratamentoRepository tipoTratamentoRepository, SessaoRepository sessaoRepository,
            AvaliacaoRepository avaliacaoRepository, PrelecaoRepository prelecaoRepository,
            SessaoAssistenciaRepository sessaoAssistenciaRepository, PasswordEncoder passwordEncoder,
            @Value("${divinaluz.demo.admin-senha:}") String senhaAdmin) {
        this.assistidoRepository = assistidoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
        this.tipoTratamentoRepository = tipoTratamentoRepository;
        this.sessaoRepository = sessaoRepository;
        this.avaliacaoRepository = avaliacaoRepository;
        this.prelecaoRepository = prelecaoRepository;
        this.sessaoAssistenciaRepository = sessaoAssistenciaRepository;
        this.passwordEncoder = passwordEncoder;
        this.senhaAdmin = senhaAdmin;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (senhaAdmin == null || senhaAdmin.isBlank()) {
            throw new IllegalStateException("Perfil demo sem senha do admin: defina a variável de ambiente "
                    + "DEMO_ADMIN_SENHA. A senha provisória das migrations não pode valer numa URL pública.");
        }
        definirSenhaAdmin();

        boolean jaTemDados = assistidoRepository.findAll().stream().anyMatch(a -> a.getLogin() == null);
        if (jaTemDados) {
            log.info("Demo: dados já cadastrados, nada a fazer além da senha do admin.");
            return;
        }
        cadastrarDados(LocalDate.now());
        log.info("Demo: dados fictícios cadastrados.");
    }

    private void definirSenhaAdmin() {
        Assistido admin = assistidoRepository.findByLoginAndAcessoAtivoTrue("admin")
                .orElseThrow(() -> new IllegalStateException("Login admin não encontrado (migrations V19–V25)."));
        admin.setSenha(passwordEncoder.encode(senhaAdmin));
        admin.setPerfilAcesso(PerfilAcesso.ADMINISTRADOR);
        assistidoRepository.save(admin);
    }

    // ---------------------------------------------------------------- dados

    private void cadastrarDados(LocalDate hoje) {
        // Sessões anteriores (nunca hoje, para a sessão de hoje ficar livre para abrir na apresentação).
        LocalDate dom1 = hoje.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        LocalDate dom2 = dom1.minusWeeks(1);
        LocalDate dom3 = dom1.minusWeeks(2);
        LocalDate dom4 = dom1.minusWeeks(3);
        LocalDate dom6 = dom1.minusWeeks(5);
        LocalDate ter1 = hoje.with(TemporalAdjusters.previous(DayOfWeek.TUESDAY));
        LocalDate ter2 = ter1.minusWeeks(1);
        LocalDate ter3 = ter1.minusWeeks(2);
        LocalDate proxima = SessaoService.proximaDataDeSessao(hoje);

        // Trabalhadores (também são assistidos em tratamento).
        Trabalhador paulo = trabalhador("Paulo Henrique Duarte", DiaFrequencia.DOMINGO_08H, "P2",
                Set.of(TipoTrabalhador.DIRIGENTE, TipoTrabalhador.EXPOSITOR_PRELETOR), dom2, dom1);
        Trabalhador lucia = trabalhador("Lúcia Ferraz", DiaFrequencia.TERCA_19H, "CH",
                Set.of(TipoTrabalhador.EXPOSITOR_PRELETOR), ter2, ter1);
        Trabalhador joseli = trabalhador("Joseli Martins", DiaFrequencia.DOMINGO_08H, "P2",
                Set.of(TipoTrabalhador.PASSISTA), dom3, dom2, dom1);
        Trabalhador sueli = trabalhador("Sueli Ramos", DiaFrequencia.DOMINGO_08H, "P2",
                Set.of(TipoTrabalhador.PASSISTA), dom1);
        Trabalhador cecilia = trabalhador("Cecília Andrade", DiaFrequencia.DOMINGO_08H, "CH",
                Set.of(TipoTrabalhador.PASSISTA), dom2, dom1);
        Trabalhador wando = trabalhador("Wando Teixeira", DiaFrequencia.TERCA_19H, "P2",
                Set.of(TipoTrabalhador.PASSISTA), ter1);
        Trabalhador ronaldo = trabalhador("Ronaldo Lima", DiaFrequencia.DOMINGO_08H, "P2",
                Set.of(TipoTrabalhador.FACILITADOR), dom1);
        Trabalhador tiana = trabalhador("Tiana Rocha", DiaFrequencia.DOMINGO_08H, "P1",
                Set.of(TipoTrabalhador.FACILITADOR), dom2, dom1);

        // Assistidos cobrindo cada situação do cartão.
        Assistido maria = assistido("Maria Aparecida Souza", DiaFrequencia.DOMINGO_08H, "P2",
                CartaoStatus.EM_TRATAMENTO, dom2, dom1);                       // próxima: 3ª presença
        assistido("Pedro Souza", DiaFrequencia.DOMINGO_08H, "P1",
                CartaoStatus.EM_TRATAMENTO, dom3, dom2, dom1);                  // próxima: 4ª → avaliação
        assistido("João Conceição", DiaFrequencia.DOMINGO_08H, "CH",
                CartaoStatus.EM_TRATAMENTO, dom1);
        assistido("Ana Beatriz Lopes", DiaFrequencia.DOMINGO_08H, "P2",
                CartaoStatus.EM_TRATAMENTO, dom2, dom1);
        assistido("Marta Souza", DiaFrequencia.TERCA_19H, "P2",
                CartaoStatus.EM_TRATAMENTO, ter2, ter1);                        // de terça: ouvinte ou mudar de dia
        assistido("Carlos Eduardo Nunes", DiaFrequencia.TERCA_19H, "CH",
                CartaoStatus.EM_TRATAMENTO, ter3, ter2, ter1);
        assistido("Davi Souza", DiaFrequencia.DOMINGO_08H, "P2",
                CartaoStatus.AGUARDANDO_AVALIACAO, dom4, dom3, dom2, dom1);    // cartão retido
        Assistido helena = assistido("Helena Souza", DiaFrequencia.DOMINGO_08H, "CH",
                CartaoStatus.AGUARDANDO_ENTREVISTA, dom4, dom3, dom2, dom1);   // avaliada, falta entrevista
        assistido("Lucas Souza", DiaFrequencia.DOMINGO_08H, "P2",
                CartaoStatus.EM_TRATAMENTO, dom6.minusWeeks(1), dom6);          // 21+ dias: cartão expira
        assistido("Rita de Cássia Prado", DiaFrequencia.DOMINGO_08H, "P3C",
                CartaoStatus.EM_TRATAMENTO, dom1);

        Avaliacao avaliacao = new Avaliacao();
        avaliacao.setAssistido(helena);
        avaliacao.setNumeroVez(1);
        avaliacao.setData(dom1);
        avaliacao.setEvolucao(Evolucao.MELHOR);
        avaliacao.setHistorico("Relata melhora do sono e da ansiedade após as quatro sessões.");
        avaliacao.setObservacoes("Manter o Evangelho no Lar semanal.");
        avaliacaoRepository.save(avaliacao);

        // Acesso de consulta para mostrar a visão do assistido (o próprio cartão e a escala).
        maria.setLogin(LOGIN_ASSISTIDO_DEMO);
        maria.setSenha(passwordEncoder.encode(senhaAdmin));
        maria.setPerfilAcesso(PerfilAcesso.ASSISTIDO);
        assistidoRepository.save(maria);

        // Escala de preleções: as duas últimas sessões e as próximas quatro.
        String[] temas = {"Evangelho no Lar", "Amar e Perdoar", "A Prece", "Caridade e Humildade",
                "O Passe e a Fé", "Reforma Íntima"};
        List<LocalDate> datasPrelecao = new ArrayList<>(List.of(ter1.isAfter(dom1) ? dom1 : ter1,
                ter1.isAfter(dom1) ? ter1 : dom1));
        LocalDate data = proxima;
        while (datasPrelecao.size() < temas.length) {
            datasPrelecao.add(data);
            data = SessaoService.proximaDataDeSessao(data.plusDays(1));
        }
        for (int i = 0; i < temas.length; i++) {
            Prelecao prelecao = new Prelecao();
            prelecao.setDataApresentacao(datasPrelecao.get(i));
            prelecao.setTema(temas[i]);
            prelecao.setPreletor(i % 2 == 0 ? paulo : lucia);
            prelecaoRepository.save(prelecao);
        }
        // As presenças já lançadas ganham a preleção da data, como o check-in faria.
        sessaoRepository.findAll().forEach(s -> prelecaoRepository.findByDataApresentacao(s.getDataConsulta())
                .ifPresent(p -> {
                    s.setPrelecao(p);
                    sessaoRepository.save(s);
                }));

        // Sessão do último domingo (encerrada) e a próxima já escalada, esperando o check-in.
        Map<PosicaoSessao, List<Trabalhador>> escala = Map.of(
                PosicaoSessao.DIRIGENTE, List.of(paulo),
                PosicaoSessao.RECEPCIONISTA, List.of(ronaldo, tiana),
                PosicaoSessao.ENTREVISTADOR, List.of(tiana),
                PosicaoSessao.PASSE_LIMPEZA, List.of(sueli),
                PosicaoSessao.CAMARA_PASSE, List.of(joseli, cecilia, wando, sueli),
                PosicaoSessao.DIRIGENTE_CAMARA, List.of(joseli),
                PosicaoSessao.P3B, List.of(sueli, cecilia));
        SessaoAssistencia passada = sessao(dom1, escala);
        passada.setCheckinAbertoEm(dom1.atTime(7, 40));
        passada.setCheckinFechadoEm(dom1.atTime(10, 5));
        sessaoAssistenciaRepository.save(passada);
        sessao(proxima, escala);
    }

    private Trabalhador trabalhador(String nome, DiaFrequencia dia, String tratamento, Set<TipoTrabalhador> funcoes,
            LocalDate... presencas) {
        Assistido assistido = assistido(nome, dia, tratamento, CartaoStatus.EM_TRATAMENTO, presencas);
        assistido.setVinculo("TRABALHADOR");
        assistidoRepository.save(assistido);
        Trabalhador trabalhador = new Trabalhador();
        trabalhador.setAssistido(assistido);
        trabalhador.getFuncoes().addAll(funcoes);
        return trabalhadorRepository.save(trabalhador);
    }

    /** Assistido com presenças efetivas numeradas a partir de 1 (a 1ª data inicia o ciclo). */
    private Assistido assistido(String nome, DiaFrequencia dia, String tratamento, CartaoStatus status,
            LocalDate... presencas) {
        Assistido assistido = new Assistido();
        assistido.setNome(nome);
        assistido.setVinculo("ASSISTIDO");
        assistido.setDiaFrequencia(dia);
        assistido.setTratamentoAtual(tratamento(tratamento));
        assistido.setStatusCartao(status);
        assistido.setCicloIniciadoEm(presencas.length > 0 ? presencas[0] : null);
        assistido.setCodigoCartao(UUID.randomUUID().toString());
        assistidoRepository.save(assistido);

        for (int i = 0; i < presencas.length; i++) {
            SessaoTratamento presenca = new SessaoTratamento();
            presenca.setAssistido(assistido);
            presenca.setDataConsulta(presencas[i]);
            presenca.setNumeroSerie(i + 1);
            presenca.setOuvinte(false);
            sessaoRepository.save(presenca);
        }
        return assistido;
    }

    private SessaoAssistencia sessao(LocalDate data, Map<PosicaoSessao, List<Trabalhador>> escala) {
        SessaoAssistencia sessao = new SessaoAssistencia();
        sessao.setData(data);
        sessao.setDiaFrequencia(data.getDayOfWeek() == DayOfWeek.TUESDAY
                ? DiaFrequencia.TERCA_19H : DiaFrequencia.DOMINGO_08H);
        escala.forEach((posicao, trabalhadores) -> trabalhadores.forEach(t -> {
            EscalaSessao item = new EscalaSessao();
            item.setSessao(sessao);
            item.setTrabalhador(t);
            item.setPosicao(posicao);
            sessao.getEscala().add(item);
        }));
        return sessaoAssistenciaRepository.save(sessao);
    }

    private TipoTratamento tratamento(String codigo) {
        return tipoTratamentoRepository.findByCodigo(codigo)
                .orElseThrow(() -> new IllegalStateException("Tratamento " + codigo + " não está no catálogo (V3)."));
    }
}
