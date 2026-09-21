package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.AvaliacaoPendenteException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Avaliacao;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class TratamentoService {

    private static final int DIAS_ENTRE_SESSOES = 7;
    private static final int DIAS_TOLERANCIA_AUSENCIA = 21; // 3 semanas sem sessão reinicia o tratamento em P2
    private static final int SESSOES_POR_AVALIACAO = 4;
    private static final String CODIGO_TRATAMENTO_INICIAL = "P2";

    private final SessaoRepository sessaoRepository;
    private final AvaliacaoRepository avaliacaoRepository;
    private final AssistidoRepository assistidoRepository;
    private final TipoTratamentoRepository tipoTratamentoRepository;

    public TratamentoService(SessaoRepository sessaoRepository, AvaliacaoRepository avaliacaoRepository,
            AssistidoRepository assistidoRepository, TipoTratamentoRepository tipoTratamentoRepository) {
        this.sessaoRepository = sessaoRepository;
        this.avaliacaoRepository = avaliacaoRepository;
        this.assistidoRepository = assistidoRepository;
        this.tipoTratamentoRepository = tipoTratamentoRepository;
    }

    public record ResultadoSessao(SessaoTratamento sessao, boolean tratamentoReiniciado) {}

    @Transactional
    public ResultadoSessao registrarSessao(SessaoTratamento novaSessao) {
        Assistido assistido = novaSessao.getAssistido();
        Long assistidoId = assistido.getId();

        Optional<SessaoTratamento> ultimaSessao =
                sessaoRepository.findFirstByAssistidoIdOrderByDataConsultaDesc(assistidoId);
        boolean tratamentoReiniciado = false;

        if (ultimaSessao.isPresent()) {
            long dias = ChronoUnit.DAYS.between(ultimaSessao.get().getDataConsulta(), novaSessao.getDataConsulta());

            if (dias < DIAS_ENTRE_SESSOES) {
                throw new RegraNegocioException(
                        "O assistido já realizou uma sessão nos últimos 7 dias. Próxima sessão liberada em: "
                                + ultimaSessao.get().getDataConsulta().plusDays(DIAS_ENTRE_SESSOES));
            }

            if (dias >= DIAS_TOLERANCIA_AUSENCIA) {
                // Passou de 2 semanas de tolerância (faltou na 3ª semana): reinicia o tratamento em P2,
                // independente do tratamento anterior, e zera a contagem do ciclo (regra das 4 sessões).
                reiniciarTratamento(assistido, novaSessao.getDataConsulta());
                tratamentoReiniciado = true;
            }
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
        long totalAvaliacoesCiclo = contarAvaliacoesDoCiclo(assistidoId, cicloIniciadoEm);

        // Regra das 4 sessões: a cada bloco de 4 sessões do ciclo atual é exigida uma nova avaliação.
        // Ex.: ao completar a 4ª sessão são necessárias >= 1 avaliação para liberar a 5ª;
        // ao completar a 8ª, >= 2 avaliações para liberar a 9ª; e assim por diante.
        long blocosConcluidos = totalSessoesCiclo / SESSOES_POR_AVALIACAO;
        if (totalSessoesCiclo > 0 && totalSessoesCiclo % SESSOES_POR_AVALIACAO == 0
                && totalAvaliacoesCiclo < blocosConcluidos) {
            throw new AvaliacaoPendenteException(
                    "O assistido completou " + totalSessoesCiclo
                            + " sessões neste ciclo e precisa passar por uma Nova Avaliação antes de continuar.");
        }

        novaSessao.setNumeroSerie((int) totalSessoesCiclo + 1);
        SessaoTratamento sessaoSalva = sessaoRepository.save(novaSessao);
        return new ResultadoSessao(sessaoSalva, tratamentoReiniciado);
    }

    @Transactional
    public Avaliacao registrarAvaliacao(Avaliacao novaAvaliacao) {
        Assistido assistido = novaAvaliacao.getAssistido();

        long totalAvaliacoesCiclo = contarAvaliacoesDoCiclo(assistido.getId(), assistido.getCicloIniciadoEm());
        novaAvaliacao.setNumeroVez((int) totalAvaliacoesCiclo + 1);

        Avaliacao avaliacaoSalva = avaliacaoRepository.save(novaAvaliacao);

        if (novaAvaliacao.getTratamentoIndicado() != null) {
            assistido.setTratamentoAtual(novaAvaliacao.getTratamentoIndicado());
            assistidoRepository.save(assistido);
        }

        return avaliacaoSalva;
    }

    private void reiniciarTratamento(Assistido assistido, LocalDate dataReinicio) {
        assistido.setTratamentoAtual(buscarTratamentoInicial());
        assistido.setCicloIniciadoEm(dataReinicio);
        assistidoRepository.save(assistido);
    }

    private TipoTratamento buscarTratamentoInicial() {
        return tipoTratamentoRepository.findByCodigo(CODIGO_TRATAMENTO_INICIAL)
                .orElseThrow(() -> new IllegalStateException(
                        "Tratamento padrão '" + CODIGO_TRATAMENTO_INICIAL + "' não encontrado no catálogo."));
    }

    private long contarSessoesDoCiclo(Long assistidoId, LocalDate cicloIniciadoEm) {
        return cicloIniciadoEm != null
                ? sessaoRepository.countByAssistidoIdAndDataConsultaGreaterThanEqual(assistidoId, cicloIniciadoEm)
                : sessaoRepository.countByAssistidoId(assistidoId);
    }

    private long contarAvaliacoesDoCiclo(Long assistidoId, LocalDate cicloIniciadoEm) {
        return cicloIniciadoEm != null
                ? avaliacaoRepository.countByAssistidoIdAndDataGreaterThanEqual(assistidoId, cicloIniciadoEm)
                : avaliacaoRepository.countByAssistidoId(assistidoId);
    }
}
