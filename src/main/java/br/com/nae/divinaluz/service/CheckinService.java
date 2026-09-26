package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.CartaoExpiradoException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
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
            "Nenhuma sessão está com o check-in aberto. Abra o check-in da sessão de hoje no painel da Sessão.";

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
    @Transactional
    public SessaoAssistencia abrirCheckin(Long sessaoId, LocalDate hoje) {
        SessaoAssistencia sessao = buscarSessao(sessaoId);

        if (!hoje.equals(sessao.getData())) {
            throw new RegraNegocioException("O check-in só pode ser aberto no dia da sessão ("
                    + sessao.getData().format(FORMATO_DATA) + ").");
        }
        if (sessao.isCheckinAberto()) {
            return sessao;
        }

        sessaoComCheckinAberto()
                .filter(aberta -> !aberta.getId().equals(sessaoId))
                .ifPresent(this::fechar);

        sessao.setCheckinAbertoEm(LocalDateTime.now());
        sessao.setCheckinFechadoEm(null);
        return sessaoAssistenciaRepository.save(sessao);
    }

    @Transactional
    public SessaoAssistencia fecharCheckin(Long sessaoId) {
        return fechar(buscarSessao(sessaoId));
    }

    private SessaoAssistencia fechar(SessaoAssistencia sessao) {
        if (sessao.isCheckinAberto()) {
            sessao.setCheckinFechadoEm(LocalDateTime.now());
        }
        return sessaoAssistenciaRepository.save(sessao);
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
            throw new RegraNegocioException("O check-in desta sessão ("
                    + sessao.getData().format(FORMATO_DATA) + ") não está aberto.");
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
