package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.AvaliacaoPendenteException;
import br.com.nae.divinaluz.exception.CartaoExpiradoException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Avaliacao;
import br.com.nae.divinaluz.model.CartaoEncerrado;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.Entrevista;
import br.com.nae.divinaluz.model.HistoricoDiaFrequencia;
import br.com.nae.divinaluz.model.ResultadoAvaliacao;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.StatusCartaoEncerrado;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.repository.CartaoEncerradoRepository;
import br.com.nae.divinaluz.repository.EntrevistaRepository;
import br.com.nae.divinaluz.repository.HistoricoDiaFrequenciaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoAssistenciaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

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
    private final HistoricoDiaFrequenciaRepository historicoDiaFrequenciaRepository;
    private final CartaoEncerradoRepository cartaoEncerradoRepository;
    private final SessaoAssistenciaRepository sessaoAssistenciaRepository;

    public TratamentoService(SessaoRepository sessaoRepository, AvaliacaoRepository avaliacaoRepository,
            EntrevistaRepository entrevistaRepository, AssistidoRepository assistidoRepository,
            TipoTratamentoRepository tipoTratamentoRepository, PrelecaoRepository prelecaoRepository,
            HistoricoDiaFrequenciaRepository historicoDiaFrequenciaRepository,
            CartaoEncerradoRepository cartaoEncerradoRepository,
            SessaoAssistenciaRepository sessaoAssistenciaRepository) {
        this.sessaoRepository = sessaoRepository;
        this.avaliacaoRepository = avaliacaoRepository;
        this.entrevistaRepository = entrevistaRepository;
        this.assistidoRepository = assistidoRepository;
        this.tipoTratamentoRepository = tipoTratamentoRepository;
        this.prelecaoRepository = prelecaoRepository;
        this.historicoDiaFrequenciaRepository = historicoDiaFrequenciaRepository;
        this.cartaoEncerradoRepository = cartaoEncerradoRepository;
        this.sessaoAssistenciaRepository = sessaoAssistenciaRepository;
    }

    // aposAlta: a pessoa tinha recebido alta — ou entrou como ouvinte, ou (com a confirmação da
    // recepção) começou um tratamento novo em P2. Muda só a mensagem que a tela mostra.
    public record ResultadoSessao(SessaoTratamento sessao, boolean tratamentoReiniciado, boolean ouvinte,
            boolean aposAlta) {
        public ResultadoSessao(SessaoTratamento sessao, boolean tratamentoReiniciado, boolean ouvinte) {
            this(sessao, tratamentoReiniciado, ouvinte, false);
        }
    }

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
        validarSessaoNaoCancelada(novaSessao.getDataConsulta());
        if (assistido.getStatusCartao() == CartaoStatus.ALTA) {
            return registrarSessaoAposAlta(novaSessao, confirmarReinicio);
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
                        ultimaSessao.get().getDataConsulta(), novaSessao.getDataConsulta())
                        >= toleranciaAusencia(assistido, ultimaSessao.get().getDataConsulta(), novaSessao.getDataConsulta()));

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

    /**
     * Avaliação (Módulo do Avaliador, 2026-10-07): o cartão completou as 4 sessões e está "Em
     * Avaliação". O Avaliador registra o diagnóstico (histórico, evolução), as recomendações do verso
     * do cartão e decide o resultado — novo tratamento (proposto aqui) ou alta. O cartão passa a
     * Aguardando Entrevista. Não mexe em Assistido.tratamentoAtual: o que o Avaliador decidiu só vale
     * quando a Entrevista o comunica.
     *
     * <p>Controles: só com o cartão Em Avaliação (antes dava para registrar avaliação a qualquer
     * momento); ninguém avalia a si mesmo (conferido aqui, não só no controller); data não futura e
     * não anterior à presença que completou o cartão; evolução obrigatória a partir da 2ª VEZ (a 1ª
     * VEZ do cartão físico não tem M/P/I/B: não há com o que comparar).</p>
     *
     * @param responsavel quem está registrando (o login); gravado como avaliador
     */
    @Transactional
    public Avaliacao registrarAvaliacao(Avaliacao novaAvaliacao, Assistido responsavel) {
        Assistido assistido = novaAvaliacao.getAssistido();
        exigirOutraPessoa(assistido, responsavel, "avaliação");
        if (assistido.getStatusCartao() != CartaoStatus.AGUARDANDO_AVALIACAO) {
            throw new RegraNegocioException("Só é possível registrar a avaliação com o cartão \""
                    + CartaoStatus.AGUARDANDO_AVALIACAO.getLabel() + "\" (4 sessões cumpridas). O cartão está \""
                    + rotulo(assistido.getStatusCartao()) + "\".");
        }
        LocalDate data = novaAvaliacao.getData();
        if (data == null) {
            throw new RegraNegocioException("Informe a data da avaliação.");
        }
        if (data.isAfter(LocalDate.now())) {
            throw new RegraNegocioException("A data da avaliação não pode ser futura.");
        }
        sessaoRepository.findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(assistido.getId())
                .map(SessaoTratamento::getDataConsulta)
                .filter(data::isBefore)
                .ifPresent(ultima -> {
                    throw new RegraNegocioException("A data da avaliação não pode ser anterior à última presença do cartão ("
                            + ultima.format(FORMATO_DATA) + ").");
                });

        if (novaAvaliacao.getResultado() == null) {
            novaAvaliacao.setResultado(ResultadoAvaliacao.NOVO_TRATAMENTO);
        }
        if (novaAvaliacao.isAlta()) {
            novaAvaliacao.setTratamentoProposto(null);
        } else if (novaAvaliacao.getTratamentoProposto() == null) {
            throw new RegraNegocioException("Informe o tratamento proposto para as próximas sessões (ou marque Alta).");
        }

        // A VEZ conta todas as avaliações da pessoa, sem limite (o cartão físico para na 8ª só por
        // falta de espaço no papel) — contada no
        // ciclo, como era até a V42, saía sempre "1ª VEZ", porque cada entrevista reinicia o ciclo.
        int vez = (int) avaliacaoRepository.countByAssistidoId(assistido.getId()) + 1;
        if (vez > 1 && novaAvaliacao.getEvolucao() == null) {
            throw new RegraNegocioException("Informe a evolução (Melhor, Pior, Indiferente ou Bom) — "
                    + "obrigatória a partir da 2ª avaliação.");
        }
        novaAvaliacao.setNumeroVez(vez);
        novaAvaliacao.setAvaliador(responsavel);

        Avaliacao avaliacaoSalva = avaliacaoRepository.save(novaAvaliacao);
        assistido.setStatusCartao(CartaoStatus.AGUARDANDO_ENTREVISTA);
        assistidoRepository.save(assistido);
        return avaliacaoSalva;
    }

    /**
     * Entrevista: o entrevistador comunica o que o Avaliador decidiu (confirmando ou ajustando),
     * registra a observação e libera o cartão. A data precisa cair no dia de assistência do assistido
     * (item 3). Com novo tratamento, abre o ciclo seguinte já com a 1ª sessão na data da entrevista;
     * com alta, encerra o tratamento.
     *
     * <p>Controles: só com o cartão Aguardando Entrevista; a avaliação precisa ser desta pessoa e
     * ainda sem entrevista; ninguém entrevista a si mesmo; data não futura e não anterior à
     * avaliação.</p>
     *
     * @param responsavel quem está registrando (o login); gravado como entrevistador
     */
    @Transactional
    public Entrevista registrarEntrevista(Entrevista novaEntrevista, Assistido responsavel) {
        Assistido assistido = novaEntrevista.getAssistido();
        Avaliacao avaliacao = novaEntrevista.getAvaliacao();
        exigirOutraPessoa(assistido, responsavel, "entrevista");

        if (avaliacao == null || (avaliacao.getAssistido() != null
                && !Objects.equals(avaliacao.getAssistido().getId(), assistido.getId()))) {
            throw new RegraNegocioException("Esta avaliação não é deste assistido.");
        }
        if (entrevistaRepository.existsByAvaliacaoId(avaliacao.getId())) {
            throw new RegraNegocioException("Esta avaliação já tem uma entrevista registrada.");
        }
        if (assistido.getStatusCartao() != CartaoStatus.AGUARDANDO_ENTREVISTA) {
            throw new RegraNegocioException("Só é possível registrar a entrevista com o cartão \""
                    + CartaoStatus.AGUARDANDO_ENTREVISTA.getLabel() + "\". O cartão está \""
                    + rotulo(assistido.getStatusCartao()) + "\".");
        }
        LocalDate data = novaEntrevista.getData();
        if (data == null) {
            throw new RegraNegocioException("Informe a data da entrevista.");
        }
        if (data.isAfter(LocalDate.now())) {
            throw new RegraNegocioException("A data da entrevista não pode ser futura.");
        }
        if (avaliacao.getData() != null && data.isBefore(avaliacao.getData())) {
            throw new RegraNegocioException("A entrevista não pode ser anterior à avaliação ("
                    + avaliacao.getData().format(FORMATO_DATA) + ").");
        }
        validarSessaoNaoCancelada(data);
        validarDiaDaSemana(assistido, data);

        if (novaEntrevista.getResultado() == null) {
            novaEntrevista.setResultado(ResultadoAvaliacao.NOVO_TRATAMENTO);
        }
        if (novaEntrevista.isAlta()) {
            novaEntrevista.setTratamentoIndicado(null);
        } else if (novaEntrevista.getTratamentoIndicado() == null) {
            throw new RegraNegocioException("Informe o tratamento das próximas sessões (ou marque Alta).");
        }
        if (responsavel != null) {
            novaEntrevista.setEntrevistadorResponsavel(responsavel);
            novaEntrevista.setEntrevistador(responsavel.getNome());
        }

        Entrevista entrevistaSalva = entrevistaRepository.save(novaEntrevista);

        // A entrevista fecha o ciclo que estava retido (4 sessões + avaliação cumpridas) — ele vai
        // para o histórico do prontuário ainda com o tratamento antigo, antes de o novo assumir.
        if (novaEntrevista.isAlta()) {
            encerrarCicloAtual(assistido, data, StatusCartaoEncerrado.ALTA);
            assistido.setTratamentoAtual(null);
            assistido.setCicloIniciadoEm(null);
            assistido.setStatusCartao(CartaoStatus.ALTA);
            assistidoRepository.save(assistido);
            return entrevistaSalva;
        }

        encerrarCicloAtual(assistido, data, StatusCartaoEncerrado.CONCLUIDO);
        assistido.setTratamentoAtual(novaEntrevista.getTratamentoIndicado());
        assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
        assistido.setCicloIniciadoEm(data);
        assistidoRepository.save(assistido);
        registrarPrimeiraSessaoDaEntrevista(assistido, data);

        return entrevistaSalva;
    }

    /**
     * Depois da alta não há cartão em andamento. A pessoa que volta entra como ouvinte; com a
     * confirmação da recepção ({@code confirmarReinicio}, o mesmo botão do reinício em P2) começa um
     * tratamento novo em P2, com esta presença como a 1ª sessão.
     */
    private ResultadoSessao registrarSessaoAposAlta(SessaoTratamento novaSessao, boolean confirmarReinicio) {
        Assistido assistido = novaSessao.getAssistido();
        if (!confirmarReinicio || existeSessaoNaSemana(assistido.getId(), novaSessao.getDataConsulta())) {
            novaSessao.setOuvinte(true);
            novaSessao.setNumeroSerie(null);
            return new ResultadoSessao(sessaoRepository.save(novaSessao), false, true, true);
        }
        validarDiaDaSemana(assistido, novaSessao.getDataConsulta());
        assistido.setTratamentoAtual(buscarTratamentoInicial());
        assistido.setCicloIniciadoEm(novaSessao.getDataConsulta());
        assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
        assistidoRepository.save(assistido);
        novaSessao.setOuvinte(false);
        novaSessao.setNumeroSerie(1);
        return new ResultadoSessao(sessaoRepository.save(novaSessao), true, false, true);
    }

    // Ninguém conduz o próprio tratamento (3.7): conferido no serviço, e não só no controller, para
    // nenhum caminho novo escapar da regra.
    private static void exigirOutraPessoa(Assistido assistido, Assistido responsavel, String atendimento) {
        if (responsavel != null && responsavel.getId() != null && responsavel.getId().equals(assistido.getId())) {
            throw new RegraNegocioException("Ninguém conduz o próprio tratamento: outro trabalhador precisa registrar esta "
                    + atendimento + ".");
        }
    }

    private static String rotulo(CartaoStatus status) {
        return status != null ? status.getLabel() : CartaoStatus.EM_TRATAMENTO.getLabel();
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

        // Cartão retido: o próximo tratamento é decidido pelo Avaliador e comunicado na entrevista.
        // Trocar por aqui pularia a avaliação inteira (2026-10-07).
        if (mudou && assistido.getStatusCartao() != null && assistido.getStatusCartao().isRetido()) {
            throw new RegraNegocioException("O cartão está \"" + assistido.getStatusCartao().getLabel()
                    + "\": o próximo tratamento é decidido pelo Avaliador e comunicado na entrevista.");
        }

        if (mudou && novoTratamento != null) {
            if (dataPrimeiraSessao == null) {
                throw new RegraNegocioException(
                        "Informe a data da 1ª sessão para iniciar o tratamento " + novoTratamento.getCodigo() + ".");
            }
            validarSessaoNaoCancelada(dataPrimeiraSessao);
            validarDiaDaSemana(assistido, dataPrimeiraSessao);

            // O ciclo em andamento é cortado no meio pela troca de tratamento: vai para o
            // histórico como interrompido, ainda com o tratamento anterior — por isso é gravado
            // antes de setTratamentoAtual.
            encerrarCicloAtual(assistido, dataPrimeiraSessao, StatusCartaoEncerrado.INTERROMPIDO);
            assistido.setTratamentoAtual(novoTratamento);

            // Só um tratamento realmente novo justifica reabrir o cartão: fora daqui, o status é
            // preservado. Setar EM_TRATAMENTO incondicionalmente (como antes) resetava por engano
            // um cartão "Aguardando Avaliação"/"Aguardando Entrevista" sempre que o formulário de
            // edição ou o card "Alterar Tratamento" eram salvos sem mudar o tratamento de fato.
            assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
            assistido.setCicloIniciadoEm(dataPrimeiraSessao);
            assistidoRepository.save(assistido);

            criarPrimeiraSessao(assistido, dataPrimeiraSessao);
        } else {
            assistido.setTratamentoAtual(novoTratamento);
            assistidoRepository.save(assistido);
        }
    }

    // Rastreabilidade do dia de assistência: toda mudança (inclusive a primeira definição) fica em
    // HistoricoDiaFrequencia. Devolve false quando o dia não mudou (nada é gravado).
    @Transactional
    public boolean alterarDiaFrequencia(Assistido assistido, DiaFrequencia novoDia, String motivo) {
        DiaFrequencia anterior = assistido.getDiaFrequencia();
        if (Objects.equals(anterior, novoDia)) {
            return false;
        }
        HistoricoDiaFrequencia log = new HistoricoDiaFrequencia();
        log.setAssistido(assistido);
        log.setDiaAnterior(anterior);
        log.setDiaNovo(novoDia);
        log.setDataHora(LocalDateTime.now());
        log.setMotivo(motivo);
        historicoDiaFrequenciaRepository.save(log);

        assistido.setDiaFrequencia(novoDia);
        assistidoRepository.save(assistido);
        return true;
    }

    /**
     * Desfaz todas as presenças (efetivas e de ouvinte) de uma data — é o que acontece quando a
     * sessão daquela data é cancelada ou excluída (2026-10-03). Para cada pessoa com presença
     * efetiva, o cartão é recalculado: as presenças seguintes do ciclo são renumeradas, o status
     * volta a refletir quantas sobraram (4 ou mais = Aguardando Avaliação) e, se a presença
     * desfeita era a do reinício em P2 por 21 dias, o ciclo que tinha expirado é restaurado — a
     * próxima presença reavalia a ausência já descontando a semana cancelada.
     *
     * <p>Recusa tudo (nada é apagado) se alguém com presença efetiva nesta data já tem Avaliação
     * ou Entrevista nesta data ou depois: esses atendimentos dependem das presenças, e desfazê-los
     * em cascata seria mexer em diagnóstico espiritual por um clique na tela da sessão.</p>
     *
     * @return quantas pessoas tiveram presença desfeita
     */
    @Transactional
    public int desfazerPresencasDaData(LocalDate data) {
        Map<Long, List<SessaoTratamento>> porAssistido = sessaoRepository.findByDataConsulta(data).stream()
                .filter(s -> s.getAssistido() != null)
                .collect(Collectors.groupingBy(s -> s.getAssistido().getId(), LinkedHashMap::new, Collectors.toList()));

        recusarSeHaAtendimentoPosterior(porAssistido.values(), data);
        porAssistido.values().forEach(linhas -> apagarERecalcular(linhas, data));
        return porAssistido.size();
    }

    /**
     * Desfaz a presença (efetiva e/ou de ouvinte) de <strong>uma</strong> pessoa numa data — o botão
     * de remover da lista "Assistidos Presentes", para quando a recepção marcou a pessoa errada
     * (2026-10-04). Mesmas regras de {@link #desfazerPresencasDaData}: o cartão é recalculado e nada é
     * apagado se ela já teve Avaliação ou Entrevista nesta data ou depois.
     *
     * @return {@code false} se ela não tinha presença nesta data
     */
    @Transactional
    public boolean desfazerPresenca(Long assistidoId, LocalDate data) {
        List<SessaoTratamento> linhas = sessaoRepository.findByDataConsulta(data).stream()
                .filter(s -> s.getAssistido() != null && assistidoId.equals(s.getAssistido().getId()))
                .toList();
        if (linhas.isEmpty()) {
            return false;
        }
        recusarSeHaAtendimentoPosterior(List.of(linhas), data);
        apagarERecalcular(linhas, data);
        return true;
    }

    private void recusarSeHaAtendimentoPosterior(java.util.Collection<List<SessaoTratamento>> porPessoa, LocalDate data) {
        List<String> comAtendimento = porPessoa.stream()
                .filter(linhas -> linhas.stream().anyMatch(s -> !s.isOuvinte()))
                .map(linhas -> linhas.get(0).getAssistido())
                .filter(a -> avaliacaoRepository.countByAssistidoIdAndDataGreaterThanEqual(a.getId(), data) > 0
                        || entrevistaRepository.countByAssistidoIdAndDataGreaterThanEqual(a.getId(), data) > 0)
                .map(Assistido::getNome)
                .toList();
        if (!comAtendimento.isEmpty()) {
            throw new RegraNegocioException("Não é possível desfazer a presença de " + data.format(FORMATO_DATA)
                    + ": " + String.join(", ", comAtendimento) + " já passou por Avaliação ou Entrevista nesta"
                    + " data ou depois, e esses atendimentos dependem das presenças.");
        }
    }

    private void apagarERecalcular(List<SessaoTratamento> linhas, LocalDate data) {
        Assistido assistido = linhas.get(0).getAssistido();
        boolean tinhaEfetiva = linhas.stream().anyMatch(s -> !s.isOuvinte());
        sessaoRepository.deleteAll(linhas);
        if (tinhaEfetiva) {
            recalcularCartaoAposDesfazer(assistido, data);
        }
    }

    private void recalcularCartaoAposDesfazer(Assistido assistido, LocalDate data) {
        // A presença desfeita abriu o ciclo atual por reinício em P2 (21 dias): volta ao ciclo que
        // tinha expirado. Os outros começos de ciclo (cadastro, troca manual de tratamento) ficam
        // como estão — o tratamento ali foi escolhido por alguém, não pela regra.
        if (data.equals(assistido.getCicloIniciadoEm())) {
            cartaoEncerradoRepository.findByAssistidoIdOrderByEncerradoEmDesc(assistido.getId()).stream()
                    .filter(c -> data.equals(c.getEncerradoEm())
                            && c.getStatusFinal() == StatusCartaoEncerrado.INCOMPLETO_POR_TEMPO)
                    .findFirst()
                    .ifPresent(expirado -> {
                        assistido.setCicloIniciadoEm(expirado.getIniciadoEm());
                        if (expirado.getTratamento() != null) {
                            assistido.setTratamentoAtual(expirado.getTratamento());
                        }
                        cartaoEncerradoRepository.delete(expirado);
                    });
        }

        LocalDate ciclo = assistido.getCicloIniciadoEm();
        List<SessaoTratamento> efetivas = sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(assistido.getId())
                .stream()
                .filter(s -> !s.isOuvinte() && s.getDataConsulta() != null
                        && (ciclo == null || !s.getDataConsulta().isBefore(ciclo)))
                .sorted(Comparator.comparing(SessaoTratamento::getDataConsulta))
                .toList();
        for (int i = 0; i < efetivas.size(); i++) {
            SessaoTratamento sessao = efetivas.get(i);
            if (!Objects.equals(sessao.getNumeroSerie(), i + 1)) {
                sessao.setNumeroSerie(i + 1);
                sessaoRepository.save(sessao);
            }
        }

        CartaoStatus status = assistido.getStatusCartao();
        if (status == null || status == CartaoStatus.EM_TRATAMENTO || status == CartaoStatus.AGUARDANDO_AVALIACAO) {
            assistido.setStatusCartao(efetivas.size() >= SESSOES_POR_AVALIACAO
                    ? CartaoStatus.AGUARDANDO_AVALIACAO : CartaoStatus.EM_TRATAMENTO);
        }
        assistidoRepository.save(assistido);
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

        validarSessaoNaoCancelada(dataPrimeiraSessao);
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
        Prelecao prelecao = prelecaoRepository.findByDataApresentacaoAndCanceladaEmIsNull(dataPrimeiraSessao).orElse(null);
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

    // Sessão cancelada = a casa não abriu: ninguém pode ter presença, 1ª sessão ou entrevista nela.
    private void validarSessaoNaoCancelada(LocalDate data) {
        if (data != null && sessaoAssistenciaRepository.existsByDataAndCanceladaEmIsNotNull(data)) {
            throw new RegraNegocioException("A sessão de " + data.format(FORMATO_DATA)
                    + " foi cancelada: a casa não abriu nessa data.");
        }
    }

    /**
     * Regra dos 21 dias descontando a casa fechada: cada semana com sessão cancelada entre as duas
     * presenças (do dia de assistência da pessoa, ou de qualquer dia se ela ainda não tem um) soma
     * 7 dias à tolerância — se a casa não abriu, ninguém faltou.
     */
    private long toleranciaAusencia(Assistido assistido, LocalDate ultimaPresenca, LocalDate novaPresenca) {
        DiaFrequencia dia = assistido.getDiaFrequencia();
        long semanasCanceladas = sessaoAssistenciaRepository
                .findByDataBetweenAndCanceladaEmIsNotNull(ultimaPresenca.plusDays(1), novaPresenca.minusDays(1))
                .stream()
                .filter(s -> dia == null || s.getDiaFrequencia() == dia)
                .map(s -> s.getData().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)))
                .distinct()
                .count();
        return DIAS_TOLERANCIA_AUSENCIA + 7L * semanasCanceladas;
    }

    private void reiniciarTratamento(Assistido assistido, LocalDate dataReinicio) {
        encerrarCicloAtual(assistido, dataReinicio, StatusCartaoEncerrado.INCOMPLETO_POR_TEMPO);
        assistido.setTratamentoAtual(buscarTratamentoInicial());
        assistido.setCicloIniciadoEm(dataReinicio);
        assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
        assistidoRepository.save(assistido);
    }

    /**
     * Fecha o ciclo em andamento e o guarda em {@link CartaoEncerrado} antes que
     * {@code cicloIniciadoEm} avance para o ciclo seguinte — sem isso o ciclo anterior sumiria
     * (item 7 da seção 8). Não grava nada quando não há ciclo aberto (assistido que nunca teve
     * primeira sessão), porque aí não existe cartão a encerrar.
     */
    private void encerrarCicloAtual(Assistido assistido, LocalDate encerradoEm, StatusCartaoEncerrado statusFinal) {
        LocalDate iniciadoEm = assistido.getCicloIniciadoEm();
        if (iniciadoEm == null) {
            return;
        }
        CartaoEncerrado cartao = new CartaoEncerrado();
        cartao.setAssistido(assistido);
        cartao.setTratamento(assistido.getTratamentoAtual());
        cartao.setIniciadoEm(iniciadoEm);
        cartao.setEncerradoEm(encerradoEm);
        cartao.setStatusFinal(statusFinal);
        cartao.setSessoesEfetivas((int) contarSessoesDoCiclo(assistido.getId(), iniciadoEm));
        cartaoEncerradoRepository.save(cartao);
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

    // A entrevista é a 1ª sessão do novo tratamento (regra 5). Se a pessoa já entrou como ouvinte
    // nesse dia (o cartão estava retido), aquela presença passa a ser a 1ª sessão — antes ela ficava
    // como ouvinte e o ciclo novo começava com zero presenças.
    private void registrarPrimeiraSessaoDaEntrevista(Assistido assistido, LocalDate data) {
        List<SessaoTratamento> doDia = sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(assistido.getId())
                .stream()
                .filter(sessao -> data.equals(sessao.getDataConsulta()))
                .toList();
        if (doDia.stream().anyMatch(sessao -> !sessao.isOuvinte())) {
            return;
        }
        if (!doDia.isEmpty()) {
            SessaoTratamento ouvinte = doDia.get(0);
            ouvinte.setOuvinte(false);
            ouvinte.setNumeroSerie(1);
            sessaoRepository.save(ouvinte);
            return;
        }
        criarPrimeiraSessao(assistido, data);
    }

    private long contarEntrevistasDoCiclo(Long assistidoId, LocalDate cicloIniciadoEm) {
        return cicloIniciadoEm != null
                ? entrevistaRepository.countByAssistidoIdAndDataGreaterThanEqual(assistidoId, cicloIniciadoEm)
                : entrevistaRepository.countByAssistidoId(assistidoId);
    }
}
