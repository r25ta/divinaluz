package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.CartaoExpiradoException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Check-in por QR code (ver CLAUDE.md 3.12). O QR fica no cartão do assistido e é a recepção que
 * escaneia: a presença só é carimbada enquanto a recepção mantiver aberta a janela de check-in da
 * preleção daquela data. Todas as regras do cartão continuam no {@link TratamentoService} — aqui só
 * se resolve "quem" (código do cartão) e "em qual sessão" (janela aberta).
 */
@Service
public class CheckinService {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PrelecaoRepository prelecaoRepository;
    private final AssistidoRepository assistidoRepository;
    private final SessaoRepository sessaoRepository;
    private final TratamentoService tratamentoService;

    public CheckinService(PrelecaoRepository prelecaoRepository, AssistidoRepository assistidoRepository,
            SessaoRepository sessaoRepository, TratamentoService tratamentoService) {
        this.prelecaoRepository = prelecaoRepository;
        this.assistidoRepository = assistidoRepository;
        this.sessaoRepository = sessaoRepository;
        this.tratamentoService = tratamentoService;
    }

    public record ResultadoCheckin(Assistido assistido, Prelecao prelecao, SessaoTratamento sessao,
            boolean ouvinte, boolean tratamentoReiniciado) {}

    public Optional<Prelecao> sessaoComCheckinAberto() {
        return prelecaoRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull();
    }

    /**
     * Abre a janela de check-in da preleção. Só é permitido na própria data da preleção — é a sessão
     * de hoje que recebe presença. Uma janela esquecida aberta em outra data é fechada aqui, senão
     * ela travaria o check-in de hoje.
     */
    @Transactional
    public Prelecao abrirCheckin(Long prelecaoId, LocalDate hoje) {
        Prelecao prelecao = buscarPrelecao(prelecaoId);

        if (!hoje.equals(prelecao.getDataApresentacao())) {
            throw new RegraNegocioException("O check-in só pode ser aberto no dia da sessão ("
                    + prelecao.getDataApresentacao().format(FORMATO_DATA) + ").");
        }
        if (prelecao.isCheckinAberto()) {
            return prelecao;
        }

        sessaoComCheckinAberto()
                .filter(aberta -> !aberta.getId().equals(prelecaoId))
                .ifPresent(this::fechar);

        prelecao.setCheckinAbertoEm(LocalDateTime.now());
        prelecao.setCheckinFechadoEm(null);
        return prelecaoRepository.save(prelecao);
    }

    @Transactional
    public Prelecao fecharCheckin(Long prelecaoId) {
        return fechar(buscarPrelecao(prelecaoId));
    }

    private Prelecao fechar(Prelecao prelecao) {
        if (prelecao.getCheckinAbertoEm() != null && prelecao.getCheckinFechadoEm() == null) {
            prelecao.setCheckinFechadoEm(LocalDateTime.now());
        }
        return prelecaoRepository.save(prelecao);
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

    /** Presença normal: aplica todas as regras do cartão (semana, ciclo de 4, 21 dias, dia da semana). */
    public ResultadoCheckin registrarPresenca(String codigoCartao) {
        return registrarPresenca(codigoCartao, false);
    }

    /** Com confirmarReinicio a recepção reinicia em P2 um cartão expirado (21 dias) e carimba a presença. */
    @Transactional(noRollbackFor = CartaoExpiradoException.class)
    public ResultadoCheckin registrarPresenca(String codigoCartao, boolean confirmarReinicio) {
        Prelecao sessao = exigirSessaoAberta();
        Assistido assistido = exigirCartaoUtilizavel(codigoCartao);

        TratamentoService.ResultadoSessao resultado = tratamentoService.registrarSessao(
                novaSessao(assistido, sessao), confirmarReinicio);
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
        Prelecao sessao = exigirSessaoAberta();
        Assistido assistido = exigirCartaoUtilizavel(codigoCartao);

        SessaoTratamento ouvinte = novaSessao(assistido, sessao);
        ouvinte.setOuvinte(true);
        ouvinte.setNumeroSerie(null);
        return new ResultadoCheckin(assistido, sessao, sessaoRepository.save(ouvinte), true, false);
    }

    private SessaoTratamento novaSessao(Assistido assistido, Prelecao sessao) {
        SessaoTratamento nova = new SessaoTratamento();
        nova.setAssistido(assistido);
        nova.setDataConsulta(sessao.getDataApresentacao());
        nova.setPrelecao(sessao);
        return nova;
    }

    private Prelecao exigirSessaoAberta() {
        return sessaoComCheckinAberto().orElseThrow(() -> new RegraNegocioException(
                "Nenhuma sessão está com o check-in aberto. Abra o check-in da sessão de hoje na escala de preleções."));
    }

    private Assistido exigirCartaoUtilizavel(String codigoCartao) {
        Assistido assistido = buscarPorCodigoCartao(codigoCartao);
        if (!assistido.isAtivo()) {
            throw new RegraNegocioException("O cadastro de " + assistido.getNome()
                    + " está inativo. Reative o prontuário antes de marcar presença.");
        }
        return assistido;
    }

    private Prelecao buscarPrelecao(Long prelecaoId) {
        return prelecaoRepository.findById(prelecaoId)
                .orElseThrow(() -> new IllegalArgumentException("Preleção inválida: " + prelecaoId));
    }
}
