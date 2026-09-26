package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.repository.EntrevistaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TratamentoServiceTest {

    @Mock
    private SessaoRepository sessaoRepository;

    @Mock
    private AvaliacaoRepository avaliacaoRepository;

    @Mock
    private EntrevistaRepository entrevistaRepository;

    @Mock
    private AssistidoRepository assistidoRepository;

    @Mock
    private TipoTratamentoRepository tipoTratamentoRepository;

    @Mock
    private PrelecaoRepository prelecaoRepository;

    @InjectMocks
    private TratamentoService tratamentoService;

    private Assistido assistido;
    private TipoTratamento tratamento;

    @BeforeEach
    void setUp() {
        assistido = new Assistido();
        assistido.setId(1L);
        assistido.setStatusCartao(CartaoStatus.EM_TRATAMENTO);

        tratamento = new TipoTratamento();
        tratamento.setId(2L);
        tratamento.setCodigo("P2");
    }

    @Test
    void segundaChegadaNaMesmaSemanaFicaComoOuvinte() {
        SessaoTratamento sessaoAnterior = sessao(LocalDate.of(2026, 9, 20));
        when(sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(1L))
                .thenReturn(List.of(sessaoAnterior));

        SessaoTratamento novaSessao = sessao(LocalDate.of(2026, 9, 22));
        TratamentoService.ResultadoSessao resultado = tratamentoService.registrarSessao(novaSessao);

        assertTrue(resultado.ouvinte());
        assertTrue(novaSessao.isOuvinte());
        assertEquals(null, novaSessao.getNumeroSerie());
        verify(sessaoRepository).save(novaSessao);
    }

    @Test
    void quartaPresencaMudaCartaoParaAguardandoAvaliacao() {
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 9, 1));
        SessaoTratamento sessaoAnterior = sessao(LocalDate.of(2026, 9, 15));
        when(sessaoRepository.findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(1L))
                .thenReturn(Optional.of(sessaoAnterior));
        when(sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(1L, LocalDate.of(2026, 9, 1)))
                .thenReturn(3L);
        when(sessaoRepository.save(any(SessaoTratamento.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SessaoTratamento novaSessao = sessao(LocalDate.of(2026, 9, 22));
        tratamentoService.registrarSessao(novaSessao);

        assertEquals(CartaoStatus.AGUARDANDO_AVALIACAO, assistido.getStatusCartao());
        assertFalse(novaSessao.isOuvinte());
        verify(assistidoRepository).save(assistido);
    }

    @Test
    void naoPermitePresencaEnquantoAguardaAvaliacao() {
        assistido.setStatusCartao(CartaoStatus.AGUARDANDO_AVALIACAO);

        assertThrows(RegraNegocioException.class,
                () -> tratamentoService.registrarSessao(sessao(LocalDate.of(2026, 9, 22))));
        verify(sessaoRepository, never()).save(any(SessaoTratamento.class));
    }

    @Test
    void resubmeterMesmoTratamentoNaoReabreCartaoAguardandoAvaliacao() {
        assistido.setTratamentoAtual(tratamento);
        assistido.setStatusCartao(CartaoStatus.AGUARDANDO_AVALIACAO);

        // Reenviar o mesmo tratamento (ex.: form de edição resubmetendo o valor já selecionado)
        // não pode reabrir um cartão que está esperando Avaliação/Entrevista.
        tratamentoService.definirTratamento(assistido, tratamento, null);

        assertEquals(CartaoStatus.AGUARDANDO_AVALIACAO, assistido.getStatusCartao());
        verify(sessaoRepository, never()).save(any(SessaoTratamento.class));
    }

    @Test
    void tratamentoRealmenteNovoReabreCartaoEmTratamento() {
        assistido.setTratamentoAtual(tratamento);
        assistido.setStatusCartao(CartaoStatus.AGUARDANDO_AVALIACAO);
        assistido.setDiaFrequencia(null);

        TipoTratamento novoTratamento = new TipoTratamento();
        novoTratamento.setId(5L);
        novoTratamento.setCodigo("P3E");

        tratamentoService.definirTratamento(assistido, novoTratamento, LocalDate.of(2026, 9, 22));

        assertEquals(CartaoStatus.EM_TRATAMENTO, assistido.getStatusCartao());
        assertEquals(novoTratamento, assistido.getTratamentoAtual());
        verify(sessaoRepository).save(any(SessaoTratamento.class));
    }

    @Test
    void primeiraSessaoAutomaticaRecebePrelecaoDaMesmaData() {
        LocalDate data = LocalDate.of(2026, 9, 22);
        Prelecao prelecao = new Prelecao();
        prelecao.setDataApresentacao(data);
        prelecao.setTema("Evangelho no Lar");
        when(prelecaoRepository.findByDataApresentacao(data)).thenReturn(Optional.of(prelecao));

        tratamentoService.iniciarTratamentoInicial(assistido, tratamento, data);

        assertEquals(tratamento, assistido.getTratamentoAtual());
        verify(sessaoRepository).save(org.mockito.ArgumentMatchers.argThat(sessao ->
                sessao.getNumeroSerie() == 1 && data.equals(sessao.getDataConsulta())
                        && sessao.getPrelecao() == prelecao));
    }

    @Test
    void ausenciaDe21DiasSemConfirmacaoMarcaIncompletoPorTempoENaoRegistra() {
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 8, 1));
        when(sessaoRepository.findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(1L))
                .thenReturn(Optional.of(sessao(LocalDate.of(2026, 9, 1))));

        assertThrows(br.com.nae.divinaluz.exception.CartaoExpiradoException.class,
                () -> tratamentoService.registrarSessao(sessao(LocalDate.of(2026, 9, 22))));

        assertEquals(CartaoStatus.INCOMPLETO_POR_TEMPO, assistido.getStatusCartao());
        verify(assistidoRepository).save(assistido);
        verify(sessaoRepository, never()).save(any(SessaoTratamento.class));
    }

    @Test
    void ausenciaDe21DiasReiniciaEmP2ComNovoCiclo() {
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 8, 1));
        when(sessaoRepository.findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(1L))
                .thenReturn(Optional.of(sessao(LocalDate.of(2026, 9, 1))));
        when(tipoTratamentoRepository.findByCodigo("P2")).thenReturn(Optional.of(tratamento));
        when(sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(1L, LocalDate.of(2026, 9, 22)))
                .thenReturn(0L);
        when(sessaoRepository.save(any(SessaoTratamento.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SessaoTratamento nova = sessao(LocalDate.of(2026, 9, 22));
        // a recepção confirma o reinício
        TratamentoService.ResultadoSessao resultado = tratamentoService.registrarSessao(nova, true);

        assertTrue(resultado.tratamentoReiniciado());
        assertEquals(tratamento, assistido.getTratamentoAtual());
        assertEquals(LocalDate.of(2026, 9, 22), assistido.getCicloIniciadoEm());
        assertEquals(CartaoStatus.EM_TRATAMENTO, assistido.getStatusCartao());
        assertEquals(1, nova.getNumeroSerie());
    }

    @Test
    void ausenciaDe14DiasNaoReiniciaTratamento() {
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 9, 1));
        when(sessaoRepository.findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(1L))
                .thenReturn(Optional.of(sessao(LocalDate.of(2026, 9, 8))));
        when(sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(1L, LocalDate.of(2026, 9, 1)))
                .thenReturn(1L);
        when(sessaoRepository.save(any(SessaoTratamento.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TratamentoService.ResultadoSessao resultado =
                tratamentoService.registrarSessao(sessao(LocalDate.of(2026, 9, 22)));

        assertFalse(resultado.tratamentoReiniciado());
        assertEquals(LocalDate.of(2026, 9, 1), assistido.getCicloIniciadoEm());
    }

    @Test
    void sessaoForaDoDiaDeAssistenciaEBloqueada() {
        assistido.setDiaFrequencia(br.com.nae.divinaluz.model.DiaFrequencia.TERCA_19H);

        // 2026-09-20 é domingo
        assertThrows(RegraNegocioException.class,
                () -> tratamentoService.registrarSessao(sessao(LocalDate.of(2026, 9, 20))));
        verify(sessaoRepository, never()).save(any(SessaoTratamento.class));
    }

    @Test
    void avaliacaoNumeraDentroDoCicloEMoveCartaoParaAguardandoEntrevista() {
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 9, 1));
        assistido.setStatusCartao(CartaoStatus.AGUARDANDO_AVALIACAO);
        when(avaliacaoRepository.countByAssistidoIdAndDataGreaterThanEqual(1L, LocalDate.of(2026, 9, 1)))
                .thenReturn(1L);
        when(avaliacaoRepository.save(any(br.com.nae.divinaluz.model.Avaliacao.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        br.com.nae.divinaluz.model.Avaliacao av = new br.com.nae.divinaluz.model.Avaliacao();
        av.setAssistido(assistido);
        av.setData(LocalDate.of(2026, 9, 23)); // data livre
        tratamentoService.registrarAvaliacao(av);

        assertEquals(2, av.getNumeroVez());
        assertEquals(CartaoStatus.AGUARDANDO_ENTREVISTA, assistido.getStatusCartao());
    }

    @Test
    void entrevistaAtualizaTratamentoReabreCartaoEIniciaCiclo() {
        assistido.setStatusCartao(CartaoStatus.AGUARDANDO_ENTREVISTA);
        br.com.nae.divinaluz.model.Avaliacao av = new br.com.nae.divinaluz.model.Avaliacao();
        av.setId(9L);
        when(entrevistaRepository.existsByAvaliacaoId(9L)).thenReturn(false);
        when(entrevistaRepository.save(any(br.com.nae.divinaluz.model.Entrevista.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        br.com.nae.divinaluz.model.Entrevista e = new br.com.nae.divinaluz.model.Entrevista();
        e.setAssistido(assistido);
        e.setAvaliacao(av);
        e.setData(LocalDate.of(2026, 9, 22));
        e.setTratamentoIndicado(tratamento);
        tratamentoService.registrarEntrevista(e);

        assertEquals(tratamento, assistido.getTratamentoAtual());
        assertEquals(CartaoStatus.EM_TRATAMENTO, assistido.getStatusCartao());
        assertEquals(LocalDate.of(2026, 9, 22), assistido.getCicloIniciadoEm());
        verify(sessaoRepository).save(any(SessaoTratamento.class));
    }

    @Test
    void entrevistaDuplicadaParaMesmaAvaliacaoEBloqueada() {
        br.com.nae.divinaluz.model.Avaliacao av = new br.com.nae.divinaluz.model.Avaliacao();
        av.setId(9L);
        when(entrevistaRepository.existsByAvaliacaoId(9L)).thenReturn(true);

        br.com.nae.divinaluz.model.Entrevista e = new br.com.nae.divinaluz.model.Entrevista();
        e.setAssistido(assistido);
        e.setAvaliacao(av);
        e.setData(LocalDate.of(2026, 9, 22));

        assertThrows(RegraNegocioException.class, () -> tratamentoService.registrarEntrevista(e));
    }

    private SessaoTratamento sessao(LocalDate data) {
        SessaoTratamento sessao = new SessaoTratamento();
        sessao.setAssistido(assistido);
        sessao.setDataConsulta(data);
        return sessao;
    }
}
