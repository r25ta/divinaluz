package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.CartaoExpiradoException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoAssistenciaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Check-in da sessão (ver CLAUDE.md 3.12 e 3.13). A presença só é carimbada enquanto a recepção
 * mantiver aberta a janela de check-in da sessão de assistência daquela data — seja pelo QR do
 * cartão, seja pela busca por nome no painel da sessão. Todas as regras do cartão continuam no
 * {@link TratamentoService}: aqui só se resolve "quem" e "em qual sessão".
 */
@Service
public class CheckinService {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public static final String SEM_SESSAO_ABERTA =
            "Nenhuma sessão está aberta. Abra a sessão de hoje no módulo Sessão.";

    private final SessaoAssistenciaRepository sessaoAssistenciaRepository;
    private final PrelecaoRepository prelecaoRepository;
    private final AssistidoRepository assistidoRepository;
    private final SessaoRepository sessaoRepository;
    private final TratamentoService tratamentoService;

    public CheckinService(SessaoAssistenciaRepository sessaoAssistenciaRepository,
            PrelecaoRepository prelecaoRepository, AssistidoRepository assistidoRepository,
            SessaoRepository sessaoRepository, TratamentoService tratamentoService) {
        this.sessaoAssistenciaRepository = sessaoAssistenciaRepository;
        this.prelecaoRepository = prelecaoRepository;
        this.assistidoRepository = assistidoRepository;
        this.sessaoRepository = sessaoRepository;
        this.tratamentoService = tratamentoService;
    }

    public record ResultadoCheckin(Assistido assistido, SessaoAssistencia sessao, SessaoTratamento presenca,
            boolean ouvinte, boolean tratamentoReiniciado) {}

    public Optional<SessaoAssistencia> sessaoComCheckinAberto() {
        return sessaoAssistenciaRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull();
    }

    /**
     * Abre a janela de check-in da sessão. Só é permitido na própria data da sessão — é a sessão de
     * hoje que recebe presença. Uma janela esquecida aberta em outra data é fechada aqui, senão ela
     * travaria o check-in de hoje.
     */
    /**
     * "Abrir sessão" no dia dela: desde 2026-10-04 abrir a sessão e abrir o check-in são um passo só.
     * Gera o segredo do QR da sessão na primeira abertura. Uma sessão esquecida aberta em outra data
     * é encerrada aqui, senão travaria o check-in de hoje.
     */
    @Transactional
    public SessaoAssistencia abrirCheckin(Long sessaoId, LocalDate hoje) {
        SessaoAssistencia sessao = buscarSessao(sessaoId);

        if (sessao.isCancelada()) {
            throw new RegraNegocioException("Esta sessão foi cancelada. Reative-a antes de abrir.");
        }
        if (sessao.isEncerrada()) {
            throw new RegraNegocioException("Esta sessão já foi encerrada. Use \"Reabrir sessão\" se foi engano.");
        }
        if (!hoje.equals(sessao.getData())) {
            throw new RegraNegocioException("A sessão só pode ser aberta no próprio dia ("
                    + sessao.getData().format(FORMATO_DATA) + ").");
        }
        if (sessao.isCheckinAberto()) {
            return sessao;
        }

        sessaoComCheckinAberto()
                .filter(aberta -> !aberta.getId().equals(sessaoId))
                .ifPresent(this::encerrar);

        if (sessao.getCheckinSegredo() == null) {
            sessao.setCheckinSegredo(CodigoQrSessao.novoSegredo());
        }
        sessao.setCheckinAbertoEm(LocalDateTime.now());
        sessao.setCheckinFechadoEm(null);
        return sessaoAssistenciaRepository.save(sessao);
    }

    /**
     * "Encerrar sessão" (2026-10-04): consolida o dia — fecha o check-in e marca a sessão como
     * encerrada. A partir daí presenças, escala e preletor ficam travados.
     */
    @Transactional
    public SessaoAssistencia encerrarSessao(Long sessaoId) {
        SessaoAssistencia sessao = buscarSessao(sessaoId);
        if (sessao.isCancelada()) {
            throw new RegraNegocioException("Esta sessão foi cancelada; não há o que encerrar.");
        }
        return encerrar(sessao);
    }

    /**
     * Desfaz um encerramento feito cedo demais. Só no próprio dia, como a abertura: encerrar é o fim do
     * trabalho, e reabrir uma sessão de outro dia mexeria em presenças que já foram consolidadas.
     */
    @Transactional
    public SessaoAssistencia reabrirSessao(Long sessaoId, LocalDate hoje) {
        SessaoAssistencia sessao = buscarSessao(sessaoId);
        if (!sessao.isEncerrada()) {
            return sessao;
        }
        if (!hoje.equals(sessao.getData())) {
            throw new RegraNegocioException("Só dá para reabrir a sessão no próprio dia ("
                    + sessao.getData().format(FORMATO_DATA) + ").");
        }
        sessao.setEncerradaEm(null);
        sessao.setEncerradaAutomaticamente(false);
        sessaoAssistenciaRepository.save(sessao);
        return abrirCheckin(sessaoId, hoje);
    }

    /**
     * Encerramento automático (2026-10-05): a sessão pode ficar aberta até 23:59:59 do próprio dia; a
     * que ninguém encerrou é encerrada pelo sistema, com esse horário. Chamado à meia-noite e na subida
     * da aplicação (ver EncerramentoAutomaticoSessoes). Devolve quantas sessões foram encerradas.
     */
    @Transactional
    public int encerrarSessoesVencidas(LocalDate hoje) {
        var vencidas = sessaoAssistenciaRepository
                .findByDataBeforeAndCheckinAbertoEmIsNotNullAndEncerradaEmIsNullAndCanceladaEmIsNull(hoje);
        for (SessaoAssistencia sessao : vencidas) {
            LocalDateTime fimDoDia = sessao.getData().atTime(23, 59, 59);
            if (sessao.isCheckinAberto()) {
                sessao.setCheckinFechadoEm(fimDoDia);
            }
            sessao.setEncerradaEm(fimDoDia);
            sessao.setEncerradaAutomaticamente(true);
            sessaoAssistenciaRepository.save(sessao);
        }
        return vencidas.size();
    }

    private SessaoAssistencia encerrar(SessaoAssistencia sessao) {
        LocalDateTime agora = LocalDateTime.now();
        if (sessao.isCheckinAberto()) {
            sessao.setCheckinFechadoEm(agora);
        }
        if (sessao.getEncerradaEm() == null) {
            sessao.setEncerradaEm(agora);
        }
        return sessaoAssistenciaRepository.save(sessao);
    }

    // ---------------------------------------------------------------- QR da sessão (autoatendimento)

    /** Resultado de quem marcou a própria presença pelo QR da sessão. */
    public record ResultadoAutoCheckin(SessaoAssistencia sessao, Assistido assistido, SessaoTratamento presenca,
            boolean ouvinte, boolean jaEstavaPresente) {}

    /**
     * Código atual do QR da sessão (muda a cada minuto). Só existe com a sessão aberta.
     *
     * <p>O segredo é gerado aqui se faltar: uma sessão aberta antes de o QR da sessão existir (o deploy
     * de 2026-10-04 chegou com a sessão de domingo já aberta) não tinha segredo, e a imagem dava 500.
     * Gerar na primeira vez que alguém pede o QR resolve sem exigir fechar e reabrir a sessão.</p>
     */
    @Transactional
    public String codigoQrAtual(Long sessaoId) {
        SessaoAssistencia sessao = exigirCheckinAberto(sessaoId);
        if (sessao.getCheckinSegredo() == null) {
            sessao.setCheckinSegredo(CodigoQrSessao.novoSegredo());
            sessaoAssistenciaRepository.save(sessao);
        }
        return CodigoQrSessao.codigo(sessao.getCheckinSegredo(), java.time.Instant.now());
    }

    /**
     * Confere o código do QR da sessão escaneado pelo celular. Devolve a sessão quando ele vale; senão
     * explica o motivo em linguagem de quem está na fila da recepção.
     */
    public SessaoAssistencia conferirQrDaSessao(Long sessaoId, String codigo) {
        SessaoAssistencia sessao = sessaoAssistenciaRepository.findById(sessaoId)
                .orElseThrow(() -> new RegraNegocioException("Este QR code não é de uma sessão da casa."));
        if (!sessao.isCheckinAberto()) {
            throw new RegraNegocioException("A sessão de " + sessao.getData().format(FORMATO_DATA)
                    + " não está aberta. Procure a recepção.");
        }
        if (!CodigoQrSessao.valido(sessao.getCheckinSegredo(), codigo, java.time.Instant.now())) {
            throw new RegraNegocioException("Este QR code expirou — ele muda a cada minuto. "
                    + "Escaneie de novo o QR que está na recepção.");
        }
        return sessao;
    }

    /**
     * O próprio assistido marca a presença escaneando o QR da sessão com o celular (2026-10-04). Passa
     * por TODAS as regras do cartão no {@link TratamentoService}: só com o cartão Em Tratamento, regra
     * dos 21 dias (sem o reinício em P2, que é decisão da recepção), dia de assistência e uma presença
     * efetiva por semana. Marcar duas vezes na mesma sessão não cria outra linha.
     */
    @Transactional(noRollbackFor = CartaoExpiradoException.class)
    public ResultadoAutoCheckin registrarAutoCheckin(Long sessaoId, String codigo, Assistido assistido) {
        SessaoAssistencia sessao = conferirQrDaSessao(sessaoId, codigo);
        exigirCartaoUtilizavel(assistido);

        Optional<SessaoTratamento> jaPresente = sessaoRepository.findByDataConsulta(sessao.getData()).stream()
                .filter(s -> s.getAssistido() != null && assistido.getId().equals(s.getAssistido().getId()))
                .findFirst();
        if (jaPresente.isPresent()) {
            return new ResultadoAutoCheckin(sessao, assistido, jaPresente.get(), jaPresente.get().isOuvinte(), true);
        }

        CartaoStatus status = assistido.getStatusCartao();
        if (status == CartaoStatus.AGUARDANDO_AVALIACAO || status == CartaoStatus.AGUARDANDO_ENTREVISTA) {
            throw new RegraNegocioException("Seu cartão está " + status.getLabel().toLowerCase()
                    + ". Procure a recepção para ser encaminhado(a).");
        }
        if (status == CartaoStatus.INCOMPLETO_POR_TEMPO) {
            throw new RegraNegocioException("Seu cartão expirou por ausência (3 semanas ou mais sem sessão). "
                    + "Procure a recepção para reiniciar o tratamento.");
        }

        try {
            TratamentoService.ResultadoSessao resultado =
                    tratamentoService.registrarSessao(novaPresenca(assistido, sessao), false);
            return new ResultadoAutoCheckin(sessao, assistido, resultado.sessao(), resultado.ouvinte(), false);
        } catch (CartaoExpiradoException e) {
            // Continua sendo CartaoExpiradoException: o status INCOMPLETO_POR_TEMPO gravado pelo
            // TratamentoService precisa sobreviver (noRollbackFor). Reiniciar é decisão da recepção.
            throw new CartaoExpiradoException("Seu cartão expirou por ausência (3 semanas ou mais sem sessão). "
                    + "Procure a recepção para reiniciar o tratamento.");
        } catch (RegraNegocioException e) {
            // Ex.: dia de assistência diferente — a recepção resolve (ouvinte ou mudança de dia).
            throw new RegraNegocioException(e.getMessage() + " Procure a recepção.");
        }
    }

    /**
     * Gera o código do QR na primeira vez que o cartão precisa dele (a V26 já preencheu quem existia
     * antes, então isto só vale para cadastros novos).
     */
    @Transactional
    public String garantirCodigoCartao(Assistido assistido) {
        if (assistido.getCodigoCartao() == null || assistido.getCodigoCartao().isBlank()) {
            assistido.setCodigoCartao(UUID.randomUUID().toString());
            assistidoRepository.save(assistido);
        }
        return assistido.getCodigoCartao();
    }

    public Assistido buscarPorCodigoCartao(String codigoCartao) {
        return assistidoRepository.findByCodigoCartao(codigoCartao)
                .orElseThrow(() -> new RegraNegocioException(
                        "Este QR code não corresponde a nenhum cartão de assistência."));
    }

    /** Presença normal pelo QR: aplica todas as regras do cartão (semana, ciclo de 4, 21 dias, dia da semana). */
    public ResultadoCheckin registrarPresenca(String codigoCartao) {
        return registrarPresenca(codigoCartao, false);
    }

    /** Com confirmarReinicio a recepção reinicia em P2 um cartão expirado (21 dias) e carimba a presença. */
    @Transactional(noRollbackFor = CartaoExpiradoException.class)
    public ResultadoCheckin registrarPresenca(String codigoCartao, boolean confirmarReinicio) {
        SessaoAssistencia sessao = exigirSessaoAberta();
        return carimbar(exigirCartaoUtilizavel(buscarPorCodigoCartao(codigoCartao)), sessao, confirmarReinicio);
    }

    /** Presença pela busca por nome no painel: mesmas regras do QR, mas na sessão do painel. */
    @Transactional(noRollbackFor = CartaoExpiradoException.class)
    public ResultadoCheckin registrarPresenca(Long sessaoId, Long assistidoId, boolean confirmarReinicio) {
        SessaoAssistencia sessao = exigirCheckinAberto(sessaoId);
        return carimbar(exigirCartaoUtilizavel(buscarAssistido(assistidoId)), sessao, confirmarReinicio);
    }

    private ResultadoCheckin carimbar(Assistido assistido, SessaoAssistencia sessao, boolean confirmarReinicio) {
        TratamentoService.ResultadoSessao resultado = tratamentoService.registrarSessao(
                novaPresenca(assistido, sessao), confirmarReinicio);
        return new ResultadoCheckin(assistido, sessao, resultado.sessao(), resultado.ouvinte(),
                resultado.tratamentoReiniciado());
    }

    /**
     * Presença como ouvinte por decisão da recepção — é a saída prevista para quem aparece num dia
     * que não é o seu (ex.: assistido de terça que comparece no domingo). Não passa pela validação
     * de dia da semana nem avança o ciclo do cartão.
     */
    @Transactional
    public ResultadoCheckin registrarOuvinte(String codigoCartao) {
        SessaoAssistencia sessao = exigirSessaoAberta();
        return ouvinte(exigirCartaoUtilizavel(buscarPorCodigoCartao(codigoCartao)), sessao);
    }

    @Transactional
    public ResultadoCheckin registrarOuvinte(Long sessaoId, Long assistidoId) {
        SessaoAssistencia sessao = exigirCheckinAberto(sessaoId);
        return ouvinte(exigirCartaoUtilizavel(buscarAssistido(assistidoId)), sessao);
    }

    private ResultadoCheckin ouvinte(Assistido assistido, SessaoAssistencia sessao) {
        SessaoTratamento ouvinte = novaPresenca(assistido, sessao);
        ouvinte.setOuvinte(true);
        ouvinte.setNumeroSerie(null);
        return new ResultadoCheckin(assistido, sessao, sessaoRepository.save(ouvinte), true, false);
    }

    private SessaoTratamento novaPresenca(Assistido assistido, SessaoAssistencia sessao) {
        SessaoTratamento nova = new SessaoTratamento();
        nova.setAssistido(assistido);
        nova.setDataConsulta(sessao.getData());
        nova.setPrelecao(prelecaoRepository.findByDataApresentacao(sessao.getData()).orElse(null));
        return nova;
    }

    private SessaoAssistencia exigirSessaoAberta() {
        return sessaoComCheckinAberto().orElseThrow(() -> new RegraNegocioException(SEM_SESSAO_ABERTA));
    }

    /** O painel é de uma sessão específica: ela precisa ser a que está com o check-in aberto. */
    public SessaoAssistencia exigirCheckinAberto(Long sessaoId) {
        SessaoAssistencia sessao = buscarSessao(sessaoId);
        if (!sessao.isCheckinAberto()) {
            throw new RegraNegocioException(sessao.isEncerrada()
                    ? "A sessão de " + sessao.getData().format(FORMATO_DATA) + " já foi encerrada e o dia está consolidado."
                    : "A sessão de " + sessao.getData().format(FORMATO_DATA) + " não está aberta.");
        }
        return sessao;
    }

    private Assistido exigirCartaoUtilizavel(Assistido assistido) {
        if (!assistido.isAtivo()) {
            throw new RegraNegocioException("O cadastro de " + assistido.getNome()
                    + " está inativo. Reative o prontuário antes de marcar presença.");
        }
        return assistido;
    }

    private Assistido buscarAssistido(Long assistidoId) {
        return assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
    }

    private SessaoAssistencia buscarSessao(Long sessaoId) {
        return sessaoAssistenciaRepository.findById(sessaoId)
                .orElseThrow(() -> new IllegalArgumentException("Sessão inválida: " + sessaoId));
    }
}
