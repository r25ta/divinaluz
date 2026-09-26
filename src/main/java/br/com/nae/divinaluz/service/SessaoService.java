package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.CartaoExpiradoException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.EscalaSessao;
import br.com.nae.divinaluz.model.PosicaoSessao;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.EntrevistaRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoAssistenciaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Módulo Sessão (ver CLAUDE.md 3.13): a sessão de assistência do dia, a escala de trabalhadores por
 * posição, a troca emergencial do preletor, os indicadores em tempo real da recepção e o cadastro
 * rápido do assistido novo. A marcação de presença em si continua no {@link CheckinService}, que
 * delega as regras do cartão ao {@link TratamentoService}.
 */
@Service
public class SessaoService {

    static final int MAXIMO_RESULTADOS_BUSCA = 20;
    private static final String SEM_TRATAMENTO = "Sem tratamento";

    private final SessaoAssistenciaRepository sessaoAssistenciaRepository;
    private final PrelecaoRepository prelecaoRepository;
    private final TrabalhadorRepository trabalhadorRepository;
    private final SessaoRepository sessaoRepository;
    private final EntrevistaRepository entrevistaRepository;
    private final AssistidoRepository assistidoRepository;
    private final TipoTratamentoRepository tipoTratamentoRepository;
    private final TratamentoService tratamentoService;
    private final CheckinService checkinService;

    public SessaoService(SessaoAssistenciaRepository sessaoAssistenciaRepository,
            PrelecaoRepository prelecaoRepository, TrabalhadorRepository trabalhadorRepository,
            SessaoRepository sessaoRepository, EntrevistaRepository entrevistaRepository,
            AssistidoRepository assistidoRepository, TipoTratamentoRepository tipoTratamentoRepository,
            TratamentoService tratamentoService, CheckinService checkinService) {
        this.sessaoAssistenciaRepository = sessaoAssistenciaRepository;
        this.prelecaoRepository = prelecaoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
        this.sessaoRepository = sessaoRepository;
        this.entrevistaRepository = entrevistaRepository;
        this.assistidoRepository = assistidoRepository;
        this.tipoTratamentoRepository = tipoTratamentoRepository;
        this.tratamentoService = tratamentoService;
        this.checkinService = checkinService;
    }

    /** Preletor e tema efetivos da sessão: o da escala de preleções ou o substituto emergencial. */
    public record PreletorDaSessao(Prelecao prelecaoDaEscala, Trabalhador preletor, String tema,
            boolean substituido) {}

    /** Uma posição da escala com quem está escalado nela e quantos faltam para o mínimo. */
    public record QuadroPosicao(PosicaoSessao posicao, List<EscalaSessao> escalados, int faltam) {}

    /** Uma pessoa presente na sessão, já resolvida para a lista do painel. */
    public record Presente(Assistido assistido, String tratamento, boolean ouvinte, Integer numeroSerie,
            boolean novo) {}

    /**
     * Indicadores da sessão (protótipo do item 6). {@code porTratamento} conta só as presenças
     * efetivas, pelo tratamento atual do assistido; ouvintes ficam à parte. Trabalhadores presentes
     * também entram como assistidos em tratamento — a contagem deles é só informativa.
     * {@code novos} são os assistidos cuja 1ª presença na casa é esta sessão (entrevista sugerida) e
     * {@code retornos} são as entrevistas pós-avaliação registradas nesta data.
     */
    public record Indicadores(int totalPresentes, int efetivas, int ouvintes, Map<String, Long> porTratamento,
            long trabalhadoresPresentes, long novos, long retornos, List<Presente> presentes) {}

    // ---------------------------------------------------------------- sessão

    /** Devolve a sessão da data, criando-a se ainda não existir. Só Domingo ou Terça-feira. */
    @Transactional
    public SessaoAssistencia garantirSessao(LocalDate data) {
        if (data == null) {
            throw new RegraNegocioException("Informe a data da sessão.");
        }
        DiaFrequencia dia = diaDaSessao(data);
        return sessaoAssistenciaRepository.findByData(data).orElseGet(() -> {
            SessaoAssistencia nova = new SessaoAssistencia();
            nova.setData(data);
            nova.setDiaFrequencia(dia);
            return sessaoAssistenciaRepository.save(nova);
        });
    }

    public SessaoAssistencia buscar(Long sessaoId) {
        return sessaoAssistenciaRepository.findById(sessaoId)
                .orElseThrow(() -> new IllegalArgumentException("Sessão inválida: " + sessaoId));
    }

    public List<SessaoAssistencia> listar() {
        return sessaoAssistenciaRepository.findAllByOrderByDataDesc();
    }

    /** Data da sessão de assistência de hoje ou, se hoje não é Domingo/Terça, da próxima. */
    public static LocalDate proximaDataDeSessao(LocalDate hoje) {
        LocalDate data = hoje;
        while (data.getDayOfWeek() != DayOfWeek.SUNDAY && data.getDayOfWeek() != DayOfWeek.TUESDAY) {
            data = data.plusDays(1);
        }
        return data;
    }

    private DiaFrequencia diaDaSessao(LocalDate data) {
        for (DiaFrequencia dia : DiaFrequencia.values()) {
            if (dia.getDiaSemana() == data.getDayOfWeek()) {
                return dia;
            }
        }
        throw new RegraNegocioException("A sessão de assistência só acontece aos Domingos (08h) e Terças-feiras (19h).");
    }

    // ---------------------------------------------------------------- preletor

    public PreletorDaSessao preletorDaSessao(SessaoAssistencia sessao) {
        Prelecao daEscala = prelecaoRepository.findByDataApresentacao(sessao.getData()).orElse(null);
        Trabalhador substituto = sessao.getPreletorSubstituto();
        String temaSubstituto = preenchido(sessao.getTemaSubstituto()) ? sessao.getTemaSubstituto() : null;

        Trabalhador preletor = substituto != null ? substituto : (daEscala != null ? daEscala.getPreletor() : null);
        String tema = temaSubstituto != null ? temaSubstituto : (daEscala != null ? daEscala.getTema() : null);
        return new PreletorDaSessao(daEscala, preletor, tema, substituto != null || temaSubstituto != null);
    }

    /**
     * Troca emergencial do preletor (e, se informado, do tema) só nesta sessão — a escala de
     * preleções continua registrando quem estava escalado. Sem trabalhador e sem tema, volta a valer
     * o que está na escala.
     */
    @Transactional
    public SessaoAssistencia trocarPreletor(Long sessaoId, Long trabalhadorId, String tema) {
        SessaoAssistencia sessao = buscar(sessaoId);
        sessao.setPreletorSubstituto(trabalhadorId != null ? buscarTrabalhador(trabalhadorId) : null);
        sessao.setTemaSubstituto(preenchido(tema) ? tema.trim() : null);
        return sessaoAssistenciaRepository.save(sessao);
    }

    // ---------------------------------------------------------------- escala

    @Transactional
    public EscalaSessao escalar(Long sessaoId, PosicaoSessao posicao, Long trabalhadorId) {
        if (posicao == null || trabalhadorId == null) {
            throw new RegraNegocioException("Escolha a posição e o trabalhador.");
        }
        SessaoAssistencia sessao = buscar(sessaoId);
        Trabalhador trabalhador = buscarTrabalhador(trabalhadorId);

        boolean jaEscalado = sessao.getEscala().stream()
                .anyMatch(e -> e.getPosicao() == posicao && e.getTrabalhador().getId().equals(trabalhadorId));
        if (jaEscalado) {
            throw new RegraNegocioException(trabalhador.getAssistido().getNome()
                    + " já está escalado(a) como " + posicao.getLabel() + " nesta sessão.");
        }

        EscalaSessao escala = new EscalaSessao();
        escala.setSessao(sessao);
        escala.setTrabalhador(trabalhador);
        escala.setPosicao(posicao);
        sessao.getEscala().add(escala);
        sessaoAssistenciaRepository.save(sessao);
        return escala;
    }

    @Transactional
    public void removerDaEscala(Long sessaoId, Long escalaId) {
        SessaoAssistencia sessao = buscar(sessaoId);
        sessao.getEscala().removeIf(e -> e.getId().equals(escalaId));
        sessaoAssistenciaRepository.save(sessao);
    }

    /** Todas as posições, na ordem do enum, com os escalados e o que falta para o mínimo. */
    public List<QuadroPosicao> quadroEscala(SessaoAssistencia sessao) {
        List<QuadroPosicao> quadro = new ArrayList<>();
        for (PosicaoSessao posicao : PosicaoSessao.values()) {
            List<EscalaSessao> escalados = sessao.getEscala().stream()
                    .filter(e -> e.getPosicao() == posicao)
                    .sorted(Comparator.comparing(e -> e.getTrabalhador().getAssistido().getNome(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
            quadro.add(new QuadroPosicao(posicao, escalados, Math.max(0, posicao.getMinimo() - escalados.size())));
        }
        return quadro;
    }

    /** Trabalhadores com cadastro ativo, por nome — qualquer um pode ocupar qualquer posição. */
    public List<Trabalhador> trabalhadoresDisponiveis() {
        return trabalhadorRepository.findAll().stream()
                .filter(t -> t.getAssistido() != null && t.getAssistido().isAtivo())
                .sorted(Comparator.comparing(t -> t.getAssistido().getNome(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ---------------------------------------------------------------- indicadores

    public Indicadores indicadores(SessaoAssistencia sessao) {
        LocalDate data = sessao.getData();

        // Uma pessoa pode ter mais de uma linha na mesma data (ex.: QR escaneado duas vezes vira
        // ouvinte na segunda). Conta-se a pessoa uma vez: efetiva se alguma linha for efetiva.
        Map<Long, List<SessaoTratamento>> porAssistido = sessaoRepository.findByDataConsulta(data).stream()
                .filter(s -> s.getAssistido() != null)
                .collect(Collectors.groupingBy(s -> s.getAssistido().getId(), LinkedHashMap::new, Collectors.toList()));

        Set<Long> jaVieramAntes = porAssistido.isEmpty()
                ? Set.of()
                : sessaoRepository.findAssistidosComPresencaAntesDe(data, porAssistido.keySet());

        List<Presente> presentes = porAssistido.values().stream()
                .map(linhas -> {
                    Optional<SessaoTratamento> efetiva = linhas.stream().filter(s -> !s.isOuvinte()).findFirst();
                    Assistido assistido = linhas.get(0).getAssistido();
                    return new Presente(assistido, codigoTratamento(assistido.getTratamentoAtual()),
                            efetiva.isEmpty(), efetiva.map(SessaoTratamento::getNumeroSerie).orElse(null),
                            !jaVieramAntes.contains(assistido.getId()));
                })
                .sorted(Comparator.comparing((Presente p) -> p.assistido().getNome(), String.CASE_INSENSITIVE_ORDER))
                .toList();

        int ouvintes = (int) presentes.stream().filter(Presente::ouvinte).count();
        return new Indicadores(
                presentes.size(),
                presentes.size() - ouvintes,
                ouvintes,
                contarPorTratamento(presentes),
                presentes.stream().filter(p -> "TRABALHADOR".equals(p.assistido().getVinculo())).count(),
                presentes.stream().filter(Presente::novo).count(),
                entrevistaRepository.countByData(data),
                presentes);
    }

    // Ordem do catálogo (P1, P2, ...) para os números não "pularem" de lugar enquanto a recepção
    // vai marcando presença; tratamentos sem ninguém presente não aparecem.
    private Map<String, Long> contarPorTratamento(List<Presente> presentes) {
        Map<String, Long> contagem = presentes.stream()
                .filter(p -> !p.ouvinte())
                .collect(Collectors.groupingBy(Presente::tratamento, Collectors.counting()));

        Map<String, Long> ordenada = new LinkedHashMap<>();
        tipoTratamentoRepository.findAll().stream()
                .map(TipoTratamento::getCodigo)
                .filter(contagem::containsKey)
                .forEach(codigo -> ordenada.put(codigo, contagem.get(codigo)));
        contagem.forEach(ordenada::putIfAbsent);
        return ordenada;
    }

    private String codigoTratamento(TipoTratamento tratamento) {
        return tratamento != null && tratamento.getCodigo() != null ? tratamento.getCodigo() : SEM_TRATAMENTO;
    }

    // ---------------------------------------------------------------- recepção

    /**
     * Busca da recepção por nome, sem acento e sem diferenciar maiúsculas, entre os cadastros
     * ativos. Pede ao menos 2 letras para não despejar a casa inteira na tela.
     */
    public List<Assistido> buscarAssistidos(String termo) {
        String busca = normalizar(termo);
        if (busca.length() < 2) {
            return List.of();
        }
        return assistidoRepository.findByAtivo(true).stream()
                .filter(a -> normalizar(a.getNome()).contains(busca))
                .sorted(Comparator.comparing(Assistido::getNome, String.CASE_INSENSITIVE_ORDER))
                .limit(MAXIMO_RESULTADOS_BUSCA)
                .toList();
    }

    /**
     * Assistido que pede na recepção para mudar o dia de assistência (ex.: de Domingo para Terça):
     * o dia é trocado com registro no histórico e a presença é marcada nesta sessão. Se ele já teve
     * presença nesta semana, a regra semanal do {@link TratamentoService} faz a presença entrar como
     * ouvinte — exatamente o exemplo do item 6.
     */
    @Transactional(noRollbackFor = CartaoExpiradoException.class)
    public CheckinService.ResultadoCheckin mudarDiaEMarcarPresenca(Long sessaoId, Long assistidoId) {
        SessaoAssistencia sessao = checkinService.exigirCheckinAberto(sessaoId);
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
        tratamentoService.alterarDiaFrequencia(assistido, sessao.getDiaFrequencia(),
                "Solicitado na recepção, na sessão de " + sessao.getDiaFrequencia().getLabel() + ".");
        return checkinService.registrarPresenca(sessaoId, assistidoId, false);
    }

    /**
     * Cadastro rápido de quem vem à casa pela primeira vez: só os dados básicos, dia de assistência
     * igual ao da sessão, tratamento P2 e presença (1ª do ciclo) já marcada nesta sessão. Os demais
     * dados são completados depois pelo prontuário. Um nome igual a um cadastro ativo exige
     * confirmação, para a recepção não duplicar quem só não foi encontrado na busca.
     */
    @Transactional
    public Assistido cadastroRapido(Long sessaoId, String nome, LocalDate dataNascimento, String sexo,
            boolean confirmarHomonimo) {
        SessaoAssistencia sessao = checkinService.exigirCheckinAberto(sessaoId);
        if (!preenchido(nome)) {
            throw new RegraNegocioException("Informe o nome do assistido.");
        }
        String nomeLimpo = nome.trim().replaceAll("\\s+", " ");
        if (!confirmarHomonimo) {
            String normalizado = normalizar(nomeLimpo);
            boolean homonimo = assistidoRepository.findByAtivo(true).stream()
                    .anyMatch(a -> normalizar(a.getNome()).equals(normalizado));
            if (homonimo) {
                throw new HomonimoException("Já existe um cadastro ativo com o nome \"" + nomeLimpo
                        + "\". Procure pela busca ou confirme que é outra pessoa.");
            }
        }

        Assistido assistido = new Assistido();
        assistido.setNome(nomeLimpo);
        assistido.setDataNascimento(dataNascimento);
        assistido.setSexo(preenchido(sexo) ? sexo : null);
        assistido.setVinculo("ASSISTIDO");
        assistidoRepository.save(assistido);

        tratamentoService.alterarDiaFrequencia(assistido, sessao.getDiaFrequencia(), "Cadastro rápido na recepção.");
        TipoTratamento p2 = tipoTratamentoRepository.findByCodigo("P2")
                .orElseThrow(() -> new IllegalStateException("Tratamento padrão P2 não encontrado no catálogo."));
        tratamentoService.iniciarTratamentoInicial(assistido, p2, sessao.getData());
        checkinService.garantirCodigoCartao(assistido);
        return assistido;
    }

    /** Nome igual a um cadastro ativo: a tela oferece "é outra pessoa, cadastrar mesmo assim". */
    public static class HomonimoException extends RegraNegocioException {
        public HomonimoException(String mensagem) {
            super(mensagem);
        }
    }

    // ---------------------------------------------------------------- utilitários

    private Trabalhador buscarTrabalhador(Long trabalhadorId) {
        return trabalhadorRepository.findById(trabalhadorId)
                .orElseThrow(() -> new IllegalArgumentException("Trabalhador inválido: " + trabalhadorId));
    }

    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static boolean preenchido(String texto) {
        return texto != null && !texto.isBlank();
    }
}
