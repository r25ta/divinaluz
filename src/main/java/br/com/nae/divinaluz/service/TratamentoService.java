package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.AvaliacaoPendenteException;
import br.com.nae.divinaluz.exception.CartaoExpiradoException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Avaliacao;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.Entrevista;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.repository.EntrevistaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
public class TratamentoService {

    private static final int DIAS_TOLERANCIA_AUSENCIA = 21; // 3 semanas sem sessão reinicia o tratamento em P2
    private static final int SESSOES_POR_AVALIACAO = 4;
    private static final String CODIGO_TRATAMENTO_INICIAL = "P2";
    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SessaoRepository sessaoRepository;
    private final AvaliacaoRepository avaliacaoRepository;
    private final EntrevistaRepository entrevistaRepository;
    private final AssistidoRepository assistidoRepository;
    private final TipoTratamentoRepository tipoTratamentoRepository;
    private final PrelecaoRepository prelecaoRepository;

    public TratamentoService(SessaoRepository sessaoRepository, AvaliacaoRepository avaliacaoRepository,
            EntrevistaRepository entrevistaRepository, AssistidoRepository assistidoRepository,
            TipoTratamentoRepository tipoTratamentoRepository, PrelecaoRepository prelecaoRepository) {
        this.sessaoRepository = sessaoRepository;
        this.avaliacaoRepository = avaliacaoRepository;
        this.entrevistaRepository = entrevistaRepository;
        this.assistidoRepository = assistidoRepository;
        this.tipoTratamentoRepository = tipoTratamentoRepository;
        this.prelecaoRepository = prelecaoRepository;
    }

    public record ResultadoSessao(SessaoTratamento sessao, boolean tratamentoReiniciado, boolean ouvinte) {}

    public ResultadoSessao registrarSessao(SessaoTratamento novaSessao) {
        return registrarSessao(novaSessao, false);
    }

    // confirmarReinicio: só a recepção confirma o reinício em P2 de um cartão expirado (21 dias);
    // sem a confirmação o cartão é marcado INCOMPLETO_POR_TEMPO e a presença não entra.
    // noRollbackFor: o status INCOMPLETO_POR_TEMPO gravado precisa sobreviver à exceção.
    @Transactional(noRollbackFor = CartaoExpiradoException.class)
    public ResultadoSessao registrarSessao(SessaoTratamento novaSessao, boolean confirmarReinicio) {
        Assistido assistido = novaSessao.getAssistido();
        Long assistidoId = assistido.getId();

        if (novaSessao.getDataConsulta() == null) {
            throw new RegraNegocioException("Informe a data da sessão.");
        }
        validarDiaDaSemana(assistido, novaSessao.getDataConsulta());

        if (assistido.getStatusCartao() == null) {
            assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
        }
        if (assistido.getStatusCartao() != CartaoStatus.EM_TRATAMENTO
                && assistido.getStatusCartao() != CartaoStatus.INCOMPLETO_POR_TEMPO) {
            throw new RegraNegocioException("O cartão está com status '"
                    + assistido.getStatusCartao().getLabel()
                    + "' e não pode receber uma presença efetiva neste momento.");
        }

        if (existeSessaoNaSemana(assistidoId, novaSessao.getDataConsulta())) {
            novaSessao.setOuvinte(true);
            novaSessao.setNumeroSerie(null);
            SessaoTratamento sessaoOuvinte = sessaoRepository.save(novaSessao);
            return new ResultadoSessao(sessaoOuvinte, false, true);
        }

        Optional<SessaoTratamento> ultimaSessao =
                sessaoRepository.findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(assistidoId);
        boolean tratamentoReiniciado = false;

        boolean expirado = assistido.getStatusCartao() == CartaoStatus.INCOMPLETO_POR_TEMPO
                || (ultimaSessao.isPresent() && ChronoUnit.DAYS.between(
                        ultimaSessao.get().getDataConsulta(), novaSessao.getDataConsulta()) >= DIAS_TOLERANCIA_AUSENCIA);

        if (expirado && ultimaSessao.isPresent()) {
            // Passou de 2 semanas de tolerância (faltou na 3ª semana): o cartão expira. Quem reinicia em
            // P2 (independente do tratamento anterior, zerando o ciclo) é a recepção, nunca o assistido.
            if (!confirmarReinicio) {
                assistido.setStatusCartao(CartaoStatus.INCOMPLETO_POR_TEMPO);
                assistidoRepository.save(assistido);
                throw new CartaoExpiradoException("O assistido ficou 3 semanas ou mais sem sessão: o cartão "
                        + "expirou (Incompleto por Tempo). Confirme o reinício em P2 para registrar a presença.");
            }
            reiniciarTratamento(assistido, novaSessao.getDataConsulta());
            tratamentoReiniciado = true;
        } else if (ultimaSessao.isPresent()) {
            // dentro da tolerância: nada a fazer
        } else {
            // Primeira sessão do assistido: inicia o ciclo e, se ainda não houver tratamento definido
            // na triagem, direciona automaticamente para P2 (porta de entrada padrão).
            assistido.setCicloIniciadoEm(novaSessao.getDataConsulta());
            if (assistido.getTratamentoAtual() == null) {
                assistido.setTratamentoAtual(buscarTratamentoInicial());
            }
            assistidoRepository.save(assistido);
        }

        LocalDate cicloIniciadoEm = assistido.getCicloIniciadoEm();
        long totalSessoesCiclo = contarSessoesDoCiclo(assistidoId, cicloIniciadoEm);
        long totalEntrevistasCiclo = contarEntrevistasDoCiclo(assistidoId, cicloIniciadoEm);

        // Regra das 4 sessões: a cada bloco de 4 sessões do ciclo atual, o assistido precisa
        // passar por Avaliação + Entrevista (é a Entrevista que efetivamente decide/comunica o
        // tratamento das próximas 4 sessões, por isso é ela que libera a continuação).
        long blocosConcluidos = totalSessoesCiclo / SESSOES_POR_AVALIACAO;
        if (totalSessoesCiclo > 0 && totalSessoesCiclo % SESSOES_POR_AVALIACAO == 0
                && totalEntrevistasCiclo < blocosConcluidos) {
            throw new AvaliacaoPendenteException(
                    "O assistido completou " + totalSessoesCiclo
                            + " sessões neste ciclo e precisa passar por Avaliação e Entrevista antes de continuar.");
        }

        novaSessao.setNumeroSerie((int) totalSessoesCiclo + 1);
        novaSessao.setOuvinte(false);
        SessaoTratamento sessaoSalva = sessaoRepository.save(novaSessao);
        if (novaSessao.getNumeroSerie() >= SESSOES_POR_AVALIACAO) {
            assistido.setStatusCartao(CartaoStatus.AGUARDANDO_AVALIACAO);
            assistidoRepository.save(assistido);
        }
        return new ResultadoSessao(sessaoSalva, tratamentoReiniciado, false);
    }

    // Avaliação: dados da avaliação espiritual em si. Data livre (não precisa cair no dia de
    // assistência) — quem fica presa a esse dia é a Entrevista que a segue (ver
    // registrarEntrevista). Não mexe em Assistido.tratamentoAtual.
    @Transactional
    public Avaliacao registrarAvaliacao(Avaliacao novaAvaliacao) {
        Assistido assistido = novaAvaliacao.getAssistido();

        long totalAvaliacoesCiclo = contarAvaliacoesDoCiclo(assistido.getId(), assistido.getCicloIniciadoEm());
        novaAvaliacao.setNumeroVez((int) totalAvaliacoesCiclo + 1);

        Avaliacao avaliacaoSalva = avaliacaoRepository.save(novaAvaliacao);
        if (assistido.getStatusCartao() == CartaoStatus.AGUARDANDO_AVALIACAO) {
            assistido.setStatusCartao(CartaoStatus.AGUARDANDO_ENTREVISTA);
            assistidoRepository.save(assistido);
        }
        return avaliacaoSalva;
    }

    // Entrevista: passo seguinte à Avaliação, em que o entrevistador comunica o tratamento
    // decidido ao assistido. A data precisa cair no dia de assistência do assistido (item 3) e é
    // ela quem atualiza Assistido.tratamentoAtual e libera a continuação das sessões.
    @Transactional
    public Entrevista registrarEntrevista(Entrevista novaEntrevista) {
        Assistido assistido = novaEntrevista.getAssistido();
        validarDiaDaSemana(assistido, novaEntrevista.getData());

        if (entrevistaRepository.existsByAvaliacaoId(novaEntrevista.getAvaliacao().getId())) {
            throw new RegraNegocioException("Esta avaliação já tem uma entrevista registrada.");
        }

        Entrevista entrevistaSalva = entrevistaRepository.save(novaEntrevista);

        if (novaEntrevista.getTratamentoIndicado() != null) {
            assistido.setTratamentoAtual(novaEntrevista.getTratamentoIndicado());
        }

        assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
        assistido.setCicloIniciadoEm(novaEntrevista.getData());
        assistidoRepository.save(assistido);
        registrarPrimeiraSessaoDaEntrevista(assistido, novaEntrevista.getData());

        return entrevistaSalva;
    }

    // Item 4: sempre que um tratamento novo é atribuído (cadastro, edição ou o card "Alterar
    // Tratamento" do prontuário), a data da 1ª sessão do novo ciclo passa a ser obrigatória, e
    // essa sessão já é criada automaticamente. Se o tratamento não mudou (resubmissão do mesmo
    // valor) ou está sendo limpo (novoTratamento == null), nenhuma data é exigida.
    @Transactional
    public void definirTratamento(Assistido assistido, TipoTratamento novoTratamento, LocalDate dataPrimeiraSessao) {
        TipoTratamento anterior = assistido.getTratamentoAtual();
        Long idAnterior = anterior != null ? anterior.getId() : null;
        Long idNovo = novoTratamento != null ? novoTratamento.getId() : null;
        boolean mudou = !Objects.equals(idAnterior, idNovo);

        assistido.setTratamentoAtual(novoTratamento);

        if (mudou && novoTratamento != null) {
            if (dataPrimeiraSessao == null) {
                throw new RegraNegocioException(
                        "Informe a data da 1ª sessão para iniciar o tratamento " + novoTratamento.getCodigo() + ".");
            }
            validarDiaDaSemana(assistido, dataPrimeiraSessao);

            // Só um tratamento realmente novo justifica reabrir o cartão: fora daqui, o status é
            // preservado. Setar EM_TRATAMENTO incondicionalmente (como antes) resetava por engano
            // um cartão "Aguardando Avaliação"/"Aguardando Entrevista" sempre que o formulário de
            // edição ou o card "Alterar Tratamento" eram salvos sem mudar o tratamento de fato.
            assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
            assistido.setCicloIniciadoEm(dataPrimeiraSessao);
            assistidoRepository.save(assistido);

            criarPrimeiraSessao(assistido, dataPrimeiraSessao);
        } else {
            assistidoRepository.save(assistido);
        }
    }

    @Transactional
    public void iniciarTratamentoInicial(Assistido assistido, TipoTratamento tratamentoInicial,
            LocalDate dataPrimeiraSessao) {
        if (tratamentoInicial == null) {
            throw new RegraNegocioException("Tratamento inicial não encontrado.");
        }
        if (dataPrimeiraSessao == null) {
            throw new RegraNegocioException("Informe a data da primeira assistência.");
        }

        validarDiaDaSemana(assistido, dataPrimeiraSessao);
        assistido.setTratamentoAtual(tratamentoInicial);
        assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
        assistido.setCicloIniciadoEm(dataPrimeiraSessao);
        assistidoRepository.save(assistido);
        criarPrimeiraSessao(assistido, dataPrimeiraSessao);
    }

    private void criarPrimeiraSessao(Assistido assistido, LocalDate dataPrimeiraSessao) {
        SessaoTratamento primeiraSessao = new SessaoTratamento();
        primeiraSessao.setAssistido(assistido);
        primeiraSessao.setNumeroSerie(1);
        primeiraSessao.setDataConsulta(dataPrimeiraSessao);
        Prelecao prelecao = prelecaoRepository.findByDataApresentacao(dataPrimeiraSessao).orElse(null);
        primeiraSessao.setPrelecao(prelecao);
        sessaoRepository.save(primeiraSessao);
    }

    // Item 3: sessões e entrevistas precisam cair no dia da semana do "Dia de Assistência" do
    // assistido (terça ou domingo). Se o assistido ainda não tem esse dia definido, não há o que
    // validar — qualquer data é aceita normalmente.
    private void validarDiaDaSemana(Assistido assistido, LocalDate data) {
        DiaFrequencia dia = assistido.getDiaFrequencia();
        if (dia == null || data == null || data.getDayOfWeek() == dia.getDiaSemana()) {
            return;
        }
        String nomeDia = data.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("pt", "BR"));
        throw new RegraNegocioException(
                "A data " + data.format(FORMATO_DATA) + " cai em uma " + nomeDia
                        + ", mas o assistido frequenta às " + dia.getLabel() + ".");
    }

    private void reiniciarTratamento(Assistido assistido, LocalDate dataReinicio) {
        assistido.setTratamentoAtual(buscarTratamentoInicial());
        assistido.setCicloIniciadoEm(dataReinicio);
        assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
        assistidoRepository.save(assistido);
    }

    private TipoTratamento buscarTratamentoInicial() {
        return tipoTratamentoRepository.findByCodigo(CODIGO_TRATAMENTO_INICIAL)
                .orElseThrow(() -> new IllegalStateException(
                        "Tratamento padrão '" + CODIGO_TRATAMENTO_INICIAL + "' não encontrado no catálogo."));
    }

    private long contarSessoesDoCiclo(Long assistidoId, LocalDate cicloIniciadoEm) {
        return cicloIniciadoEm != null
                ? sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(assistidoId, cicloIniciadoEm)
                : sessaoRepository.countByAssistidoIdAndOuvinteFalse(assistidoId);
    }

    private boolean existeSessaoNaSemana(Long assistidoId, LocalDate data) {
        LocalDate inicioSemana = data.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate fimSemana = data.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        return sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(assistidoId).stream()
            .map(sessao -> sessao.getDataConsulta())
                .filter(dataSessao -> dataSessao != null)
                .anyMatch(dataSessao -> !dataSessao.isBefore(inicioSemana) && !dataSessao.isAfter(fimSemana));
    }

    private void registrarPrimeiraSessaoDaEntrevista(Assistido assistido, LocalDate data) {
        if (data == null || existeSessaoNaData(assistido.getId(), data)) {
            return;
        }
        SessaoTratamento primeiraSessao = new SessaoTratamento();
        primeiraSessao.setAssistido(assistido);
        primeiraSessao.setNumeroSerie(1);
        primeiraSessao.setDataConsulta(data);
        primeiraSessao.setOuvinte(false);
        sessaoRepository.save(primeiraSessao);
    }

    private boolean existeSessaoNaData(Long assistidoId, LocalDate data) {
        return sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(assistidoId).stream()
                .anyMatch(sessao -> data.equals(sessao.getDataConsulta()));
    }

    private long contarAvaliacoesDoCiclo(Long assistidoId, LocalDate cicloIniciadoEm) {
        return cicloIniciadoEm != null
                ? avaliacaoRepository.countByAssistidoIdAndDataGreaterThanEqual(assistidoId, cicloIniciadoEm)
                : avaliacaoRepository.countByAssistidoId(assistidoId);
    }

    private long contarEntrevistasDoCiclo(Long assistidoId, LocalDate cicloIniciadoEm) {
        return cicloIniciadoEm != null
                ? entrevistaRepository.countByAssistidoIdAndDataGreaterThanEqual(assistidoId, cicloIniciadoEm)
                : entrevistaRepository.countByAssistidoId(assistidoId);
    }
}
