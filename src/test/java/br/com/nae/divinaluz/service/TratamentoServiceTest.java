package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoEncerrado;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.HistoricoDiaFrequencia;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.StatusCartaoEncerrado;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.repository.CartaoEncerradoRepository;
import br.com.nae.divinaluz.repository.EntrevistaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.repository.HistoricoDiaFrequenciaRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoAssistenciaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Mock
    private HistoricoDiaFrequenciaRepository historicoDiaFrequenciaRepository;

    @Mock
    private CartaoEncerradoRepository cartaoEncerradoRepository;

    @Mock
    private SessaoAssistenciaRepository sessaoAssistenciaRepository;

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
        when(prelecaoRepository.findByDataApresentacaoAndCanceladaEmIsNull(data)).thenReturn(Optional.of(prelecao));

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
        // Contagem do ciclo antigo (vai para o cartão encerrado) e a do ciclo novo.
        when(sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(1L, LocalDate.of(2026, 8, 1)))
                .thenReturn(2L);
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

    // O ciclo que expirou por tempo não pode sumir: vira um cartão encerrado como "Tratamento
    // Incompleto", com o tratamento e o período daquele ciclo (item 7 da seção 8).
    @Test
    void reinicioPorTempoGuardaCartaoEncerradoComoIncompleto() {
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 8, 1));
        assistido.setTratamentoAtual(tratamento);
        when(sessaoRepository.findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(1L))
                .thenReturn(Optional.of(sessao(LocalDate.of(2026, 9, 1))));
        when(tipoTratamentoRepository.findByCodigo("P2")).thenReturn(Optional.of(tratamento));
        when(sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(1L, LocalDate.of(2026, 8, 1)))
                .thenReturn(2L);
        when(sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(1L, LocalDate.of(2026, 9, 22)))
                .thenReturn(0L);
        when(sessaoRepository.save(any(SessaoTratamento.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        tratamentoService.registrarSessao(sessao(LocalDate.of(2026, 9, 22)), true);

        ArgumentCaptor<CartaoEncerrado> captor = ArgumentCaptor.forClass(CartaoEncerrado.class);
        verify(cartaoEncerradoRepository).save(captor.capture());
        CartaoEncerrado encerrado = captor.getValue();
        assertEquals(StatusCartaoEncerrado.INCOMPLETO_POR_TEMPO, encerrado.getStatusFinal());
        assertEquals(LocalDate.of(2026, 8, 1), encerrado.getIniciadoEm());
        assertEquals(LocalDate.of(2026, 9, 22), encerrado.getEncerradoEm());
        assertEquals(tratamento, encerrado.getTratamento());
        assertEquals(2, encerrado.getSessoesEfetivas());
    }

    // A entrevista fecha o ciclo retido como concluído — e o cartão encerrado guarda o tratamento
    // ANTIGO, não o indicado pela entrevista (que só vale do ciclo seguinte em diante).
    @Test
    void entrevistaGuardaCartaoEncerradoComoConcluidoComTratamentoAntigo() {
        assistido.setStatusCartao(CartaoStatus.AGUARDANDO_ENTREVISTA);
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 8, 25));
        assistido.setTratamentoAtual(tratamento);
        when(sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(1L, LocalDate.of(2026, 8, 25)))
                .thenReturn(4L);
        br.com.nae.divinaluz.model.Avaliacao av = new br.com.nae.divinaluz.model.Avaliacao();
        av.setId(9L);
        when(entrevistaRepository.existsByAvaliacaoId(9L)).thenReturn(false);
        when(entrevistaRepository.save(any(br.com.nae.divinaluz.model.Entrevista.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TipoTratamento indicado = new TipoTratamento();
        indicado.setId(5L);
        indicado.setCodigo("P3E");

        br.com.nae.divinaluz.model.Entrevista e = new br.com.nae.divinaluz.model.Entrevista();
        e.setAssistido(assistido);
        e.setAvaliacao(av);
        e.setData(LocalDate.of(2026, 9, 22));
        e.setTratamentoIndicado(indicado);
        tratamentoService.registrarEntrevista(e);

        ArgumentCaptor<CartaoEncerrado> captor = ArgumentCaptor.forClass(CartaoEncerrado.class);
        verify(cartaoEncerradoRepository).save(captor.capture());
        CartaoEncerrado encerrado = captor.getValue();
        assertEquals(StatusCartaoEncerrado.CONCLUIDO, encerrado.getStatusFinal());
        assertEquals(tratamento, encerrado.getTratamento());
        assertEquals(4, encerrado.getSessoesEfetivas());
        // e o assistido seguiu para o tratamento indicado, no ciclo novo
        assertEquals(indicado, assistido.getTratamentoAtual());
    }

    // Trocar o tratamento no meio do ciclo também encerra o cartão anterior — como interrompido.
    @Test
    void trocaDeTratamentoGuardaCartaoEncerradoComoInterrompido() {
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 9, 1));
        assistido.setTratamentoAtual(tratamento);
        when(sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(1L, LocalDate.of(2026, 9, 1)))
                .thenReturn(3L);

        TipoTratamento novoTratamento = new TipoTratamento();
        novoTratamento.setId(5L);
        novoTratamento.setCodigo("P3E");

        tratamentoService.definirTratamento(assistido, novoTratamento, LocalDate.of(2026, 9, 22));

        ArgumentCaptor<CartaoEncerrado> captor = ArgumentCaptor.forClass(CartaoEncerrado.class);
        verify(cartaoEncerradoRepository).save(captor.capture());
        CartaoEncerrado encerrado = captor.getValue();
        assertEquals(StatusCartaoEncerrado.INTERROMPIDO, encerrado.getStatusFinal());
        assertEquals(tratamento, encerrado.getTratamento());
        assertEquals(3, encerrado.getSessoesEfetivas());
    }

    // Assistido sem ciclo aberto (nunca teve 1ª sessão) não gera cartão encerrado vazio.
    @Test
    void semCicloAbertoNaoGuardaCartaoEncerrado() {
        assistido.setCicloIniciadoEm(null);
        assistido.setTratamentoAtual(null);

        tratamentoService.definirTratamento(assistido, tratamento, LocalDate.of(2026, 9, 22));

        verify(cartaoEncerradoRepository, never()).save(any(CartaoEncerrado.class));
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
        av.setTratamentoProposto(tratamento);
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

    // Rastreabilidade do dia de assistência: a troca feita pela recepção (painel da sessão) grava o
    // histórico com o valor anterior; reenviar o mesmo dia não gera linha nova.
    @Test
    void alterarDiaFrequenciaGravaHistoricoSoQuandoMuda() {
        assistido.setDiaFrequencia(DiaFrequencia.DOMINGO_08H);

        boolean mudou = tratamentoService.alterarDiaFrequencia(assistido, DiaFrequencia.TERCA_19H, "Pedido na recepção");

        assertTrue(mudou);
        assertEquals(DiaFrequencia.TERCA_19H, assistido.getDiaFrequencia());
        ArgumentCaptor<HistoricoDiaFrequencia> captor = ArgumentCaptor.forClass(HistoricoDiaFrequencia.class);
        verify(historicoDiaFrequenciaRepository).save(captor.capture());
        assertEquals(DiaFrequencia.DOMINGO_08H, captor.getValue().getDiaAnterior());
        assertEquals(DiaFrequencia.TERCA_19H, captor.getValue().getDiaNovo());
        assertEquals("Pedido na recepção", captor.getValue().getMotivo());

        boolean mudouDeNovo = tratamentoService.alterarDiaFrequencia(assistido, DiaFrequencia.TERCA_19H, null);

        assertFalse(mudouDeNovo);
        verify(historicoDiaFrequenciaRepository).save(any());
    }

    @Test
    void avaliacaoSemTratamentoPropostoERecusada() {
        br.com.nae.divinaluz.model.Avaliacao av = new br.com.nae.divinaluz.model.Avaliacao();
        av.setAssistido(assistido);
        av.setData(LocalDate.of(2026, 9, 23));

        assertThrows(RegraNegocioException.class, () -> tratamentoService.registrarAvaliacao(av));
        verify(avaliacaoRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- sessão cancelada (2026-10-03)

    @Test
    void presencaEmSessaoCanceladaEhRecusada() {
        LocalDate data = LocalDate.of(2026, 9, 20);
        when(sessaoAssistenciaRepository.existsByDataAndCanceladaEmIsNotNull(data)).thenReturn(true);

        assertThrows(RegraNegocioException.class, () -> tratamentoService.registrarSessao(sessao(data)));
        verify(sessaoRepository, never()).save(any(SessaoTratamento.class));
    }

    @Test
    void semanaCanceladaNaoContaNaRegraDos21Dias() {
        // 06/09 → 27/09 são 21 dias: sem o cancelamento o cartão expiraria. Com a casa fechada em
        // 13/09 (domingo, o dia da pessoa), ela só faltou a uma sessão de verdade.
        assistido.setDiaFrequencia(DiaFrequencia.DOMINGO_08H);
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 9, 6));
        when(sessaoRepository.findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(1L))
                .thenReturn(Optional.of(sessao(LocalDate.of(2026, 9, 6))));
        when(sessaoAssistenciaRepository.findByDataBetweenAndCanceladaEmIsNotNull(
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 26)))
                .thenReturn(List.of(sessaoCancelada(LocalDate.of(2026, 9, 13), DiaFrequencia.DOMINGO_08H)));
        when(sessaoRepository.countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(1L, LocalDate.of(2026, 9, 6)))
                .thenReturn(1L);
        when(sessaoRepository.save(any(SessaoTratamento.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SessaoTratamento nova = sessao(LocalDate.of(2026, 9, 27));
        TratamentoService.ResultadoSessao resultado = tratamentoService.registrarSessao(nova);

        assertFalse(resultado.tratamentoReiniciado());
        assertEquals(2, nova.getNumeroSerie());
        assertEquals(CartaoStatus.EM_TRATAMENTO, assistido.getStatusCartao());
    }

    @Test
    void sessaoCanceladaDeOutroDiaNaoEstendeATolerancia() {
        // A casa fechou numa terça, mas a pessoa é de domingo: ela faltou de verdade.
        assistido.setDiaFrequencia(DiaFrequencia.DOMINGO_08H);
        when(sessaoRepository.findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(1L))
                .thenReturn(Optional.of(sessao(LocalDate.of(2026, 9, 6))));
        when(sessaoAssistenciaRepository.findByDataBetweenAndCanceladaEmIsNotNull(any(), any()))
                .thenReturn(List.of(sessaoCancelada(LocalDate.of(2026, 9, 15), DiaFrequencia.TERCA_19H)));

        assertThrows(br.com.nae.divinaluz.exception.CartaoExpiradoException.class,
                () -> tratamentoService.registrarSessao(sessao(LocalDate.of(2026, 9, 27))));
        assertEquals(CartaoStatus.INCOMPLETO_POR_TEMPO, assistido.getStatusCartao());
    }

    @Test
    void desfazerPresencaRenumeraOCicloEReabreOCartao() {
        // Ciclo com 4 presenças (Aguardando Avaliação); a de 13/09 é desfeita: sobram 3, renumeradas.
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 9, 6));
        assistido.setStatusCartao(CartaoStatus.AGUARDANDO_AVALIACAO);
        LocalDate data = LocalDate.of(2026, 9, 13);
        SessaoTratamento desfeita = presenca(data, 2);
        SessaoTratamento s1 = presenca(LocalDate.of(2026, 9, 6), 1);
        SessaoTratamento s3 = presenca(LocalDate.of(2026, 9, 20), 3);
        SessaoTratamento s4 = presenca(LocalDate.of(2026, 9, 27), 4);
        when(sessaoRepository.findByDataConsulta(data)).thenReturn(List.of(desfeita));
        when(sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(1L)).thenReturn(List.of(s4, s3, s1));

        int pessoas = tratamentoService.desfazerPresencasDaData(data);

        assertEquals(1, pessoas);
        verify(sessaoRepository).deleteAll(List.of(desfeita));
        assertEquals(1, s1.getNumeroSerie());
        assertEquals(2, s3.getNumeroSerie());
        assertEquals(3, s4.getNumeroSerie());
        assertEquals(CartaoStatus.EM_TRATAMENTO, assistido.getStatusCartao());
    }

    @Test
    void removerUmaPresencaSoMexeNaquelaPessoa() {
        // A lista "Assistidos Presentes" remove quem foi marcado por engano; os outros ficam.
        LocalDate data = LocalDate.of(2026, 9, 13);
        assistido.setCicloIniciadoEm(LocalDate.of(2026, 9, 6));
        Assistido outro = new Assistido();
        outro.setId(2L);
        SessaoTratamento minha = presenca(data, 2);
        SessaoTratamento dele = new SessaoTratamento();
        dele.setAssistido(outro);
        dele.setDataConsulta(data);
        when(sessaoRepository.findByDataConsulta(data)).thenReturn(List.of(minha, dele));

        assertTrue(tratamentoService.desfazerPresenca(1L, data));

        verify(sessaoRepository).deleteAll(List.of(minha));
        assertFalse(tratamentoService.desfazerPresenca(99L, data), "quem não tem presença na data");
    }

    @Test
    void desfazerPresencaRecusaQuemJaTemAvaliacaoDepois() {
        assistido.setNome("Fulano");
        LocalDate data = LocalDate.of(2026, 9, 27);
        when(sessaoRepository.findByDataConsulta(data)).thenReturn(List.of(presenca(data, 4)));
        when(avaliacaoRepository.countByAssistidoIdAndDataGreaterThanEqual(1L, data)).thenReturn(1L);

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> tratamentoService.desfazerPresencasDaData(data));
        assertTrue(erro.getMessage().contains("Fulano"));
        verify(sessaoRepository, never()).deleteAll(any());
    }

    @Test
    void desfazerReinicioEmP2RestauraOCicloQueTinhaExpirado() {
        LocalDate data = LocalDate.of(2026, 9, 27);
        TipoTratamento p3e = new TipoTratamento();
        p3e.setId(5L);
        p3e.setCodigo("P3E");
        assistido.setCicloIniciadoEm(data);
        assistido.setTratamentoAtual(tratamento);
        CartaoEncerrado expirado = new CartaoEncerrado();
        expirado.setIniciadoEm(LocalDate.of(2026, 8, 2));
        expirado.setEncerradoEm(data);
        expirado.setTratamento(p3e);
        expirado.setStatusFinal(StatusCartaoEncerrado.INCOMPLETO_POR_TEMPO);
        when(sessaoRepository.findByDataConsulta(data)).thenReturn(List.of(presenca(data, 1)));
        when(cartaoEncerradoRepository.findByAssistidoIdOrderByEncerradoEmDesc(1L)).thenReturn(List.of(expirado));

        tratamentoService.desfazerPresencasDaData(data);

        assertEquals(LocalDate.of(2026, 8, 2), assistido.getCicloIniciadoEm());
        assertEquals(p3e, assistido.getTratamentoAtual());
        verify(cartaoEncerradoRepository).delete(expirado);
    }

    private SessaoTratamento presenca(LocalDate data, int numeroSerie) {
        SessaoTratamento sessao = sessao(data);
        sessao.setNumeroSerie(numeroSerie);
        return sessao;
    }

    private SessaoAssistencia sessaoCancelada(LocalDate data, DiaFrequencia dia) {
        SessaoAssistencia sessao = new SessaoAssistencia();
        sessao.setData(data);
        sessao.setDiaFrequencia(dia);
        sessao.setCanceladaEm(java.time.LocalDateTime.now());
        return sessao;
    }
}
