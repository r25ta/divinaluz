package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoAssistenciaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckinServiceTest {

    @Mock
    private SessaoAssistenciaRepository sessaoAssistenciaRepository;

    @Mock
    private PrelecaoRepository prelecaoRepository;

    @Mock
    private AssistidoRepository assistidoRepository;

    @Mock
    private SessaoRepository sessaoRepository;

    @Mock
    private TratamentoService tratamentoService;

    @InjectMocks
    private CheckinService checkinService;

    private static final LocalDate DOMINGO = LocalDate.of(2026, 9, 27);

    private SessaoAssistencia sessaoDeDomingo;
    private Prelecao prelecaoDeDomingo;
    private Assistido assistido;

    @BeforeEach
    void setUp() {
        sessaoDeDomingo = new SessaoAssistencia();
        sessaoDeDomingo.setId(10L);
        sessaoDeDomingo.setData(DOMINGO);
        sessaoDeDomingo.setDiaFrequencia(DiaFrequencia.DOMINGO_08H);

        prelecaoDeDomingo = new Prelecao();
        prelecaoDeDomingo.setId(30L);
        prelecaoDeDomingo.setTema("Evangelho no Lar");
        prelecaoDeDomingo.setDataApresentacao(DOMINGO);

        assistido = new Assistido();
        assistido.setId(5L);
        assistido.setNome("Maria");
        assistido.setCodigoCartao("codigo-do-cartao");
    }

    private void comSessaoAberta() {
        sessaoDeDomingo.setCheckinAbertoEm(LocalDateTime.now());
        when(sessaoAssistenciaRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.of(sessaoDeDomingo));
    }

    @Test
    void checkinSoAbreNaDataDaSessao() {
        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> checkinService.abrirCheckin(10L, DOMINGO.minusDays(2)));

        assertTrue(erro.getMessage().contains("27/09/2026"));
        verify(sessaoAssistenciaRepository, never()).save(any());
    }

    @Test
    void abrirCheckinNaDataMarcaAJanelaComoAberta() {
        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));
        when(sessaoAssistenciaRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.empty());
        when(sessaoAssistenciaRepository.save(any(SessaoAssistencia.class))).thenAnswer(i -> i.getArgument(0));

        SessaoAssistencia aberta = checkinService.abrirCheckin(10L, DOMINGO);

        assertNotNull(aberta.getCheckinAbertoEm());
        assertNull(aberta.getCheckinFechadoEm());
        assertTrue(aberta.isCheckinAberto());
    }

    // Uma janela esquecida aberta em outra data travaria o check-in de hoje, então ela é fechada.
    @Test
    void abrirCheckinFechaJanelaEsquecidaDeOutraData() {
        SessaoAssistencia esquecida = new SessaoAssistencia();
        esquecida.setId(9L);
        esquecida.setData(DOMINGO.minusWeeks(1));
        esquecida.setCheckinAbertoEm(LocalDateTime.now().minusDays(7));

        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));
        when(sessaoAssistenciaRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.of(esquecida));
        when(sessaoAssistenciaRepository.save(any(SessaoAssistencia.class))).thenAnswer(i -> i.getArgument(0));

        checkinService.abrirCheckin(10L, DOMINGO);

        assertNotNull(esquecida.getCheckinFechadoEm(), "a janela antiga deveria ter sido fechada");
        assertNotNull(esquecida.getEncerradaEm(), "a sessão esquecida aberta fica encerrada");
    }

    // ---------------------------------------------------------------- abrir / encerrar / reabrir (2026-10-04)

    @Test
    void abrirSessaoGeraOSegredoDoQr() {
        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));
        when(sessaoAssistenciaRepository.save(any(SessaoAssistencia.class))).thenAnswer(i -> i.getArgument(0));

        checkinService.abrirCheckin(10L, DOMINGO);

        assertNotNull(sessaoDeDomingo.getCheckinSegredo());
    }

    @Test
    void encerrarConsolidaOFimDoDia() {
        sessaoDeDomingo.setCheckinAbertoEm(LocalDateTime.now());
        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));
        when(sessaoAssistenciaRepository.save(any(SessaoAssistencia.class))).thenAnswer(i -> i.getArgument(0));

        checkinService.encerrarSessao(10L);

        assertTrue(sessaoDeDomingo.isEncerrada());
        assertTrue(!sessaoDeDomingo.isCheckinAberto());
    }

    @Test
    void sessaoEncerradaNaoReabrePeloAbrirNemEmOutroDia() {
        sessaoDeDomingo.setEncerradaEm(LocalDateTime.now());
        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));

        assertThrows(RegraNegocioException.class, () -> checkinService.abrirCheckin(10L, DOMINGO));
        assertThrows(RegraNegocioException.class, () -> checkinService.reabrirSessao(10L, DOMINGO.plusDays(1)));
        assertTrue(sessaoDeDomingo.isEncerrada());
    }

    @Test
    void reabrirNoProprioDiaVoltaAAbrir() {
        sessaoDeDomingo.setCheckinAbertoEm(LocalDateTime.now().minusHours(2));
        sessaoDeDomingo.setCheckinFechadoEm(LocalDateTime.now().minusHours(1));
        sessaoDeDomingo.setEncerradaEm(LocalDateTime.now().minusHours(1));
        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));
        when(sessaoAssistenciaRepository.save(any(SessaoAssistencia.class))).thenAnswer(i -> i.getArgument(0));

        checkinService.reabrirSessao(10L, DOMINGO);

        assertTrue(!sessaoDeDomingo.isEncerrada());
        assertTrue(sessaoDeDomingo.isCheckinAberto());
    }

    // ---------------------------------------------------------------- QR da sessão (autoatendimento)

    private String qrValido() {
        sessaoDeDomingo.setCheckinAbertoEm(LocalDateTime.now());
        sessaoDeDomingo.setCheckinSegredo(CodigoQrSessao.novoSegredo());
        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));
        return CodigoQrSessao.codigo(sessaoDeDomingo.getCheckinSegredo(), java.time.Instant.now());
    }

    @Test
    void autoCheckinComCodigoVencidoOuSessaoFechadaERecusado() {
        qrValido();
        assertThrows(RegraNegocioException.class,
                () -> checkinService.registrarAutoCheckin(10L, "AAAAAAAAAAAAAAAA", assistido));

        sessaoDeDomingo.setCheckinFechadoEm(LocalDateTime.now());
        String codigo = CodigoQrSessao.codigo(sessaoDeDomingo.getCheckinSegredo(), java.time.Instant.now());
        assertThrows(RegraNegocioException.class, () -> checkinService.registrarAutoCheckin(10L, codigo, assistido));
        verify(tratamentoService, never()).registrarSessao(any(), anyBoolean());
    }

    @Test
    void autoCheckinSoComOCartaoEmTratamento() {
        String codigo = qrValido();
        assistido.setAtivo(true);
        assistido.setStatusCartao(br.com.nae.divinaluz.model.CartaoStatus.AGUARDANDO_ENTREVISTA);

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> checkinService.registrarAutoCheckin(10L, codigo, assistido));

        assertTrue(erro.getMessage().contains("recepção"));
        verify(tratamentoService, never()).registrarSessao(any(), anyBoolean());
    }

    @Test
    void autoCheckinPassaPelasRegrasDoCartaoSemReiniciarEmP2() {
        String codigo = qrValido();
        assistido.setAtivo(true);
        assistido.setStatusCartao(br.com.nae.divinaluz.model.CartaoStatus.EM_TRATAMENTO);
        when(tratamentoService.registrarSessao(any(SessaoTratamento.class), eq(false)))
                .thenAnswer(i -> new TratamentoService.ResultadoSessao(i.getArgument(0), false, false));

        CheckinService.ResultadoAutoCheckin resultado = checkinService.registrarAutoCheckin(10L, codigo, assistido);

        assertTrue(!resultado.jaEstavaPresente());
        assertEquals(DOMINGO, resultado.presenca().getDataConsulta());
        // confirmarReinicio = false: reiniciar em P2 é decisão da recepção, nunca do próprio assistido.
        verify(tratamentoService).registrarSessao(any(SessaoTratamento.class), eq(false));
    }

    @Test
    void autoCheckinRepetidoNaMesmaSessaoNaoDuplica() {
        String codigo = qrValido();
        assistido.setAtivo(true);
        SessaoTratamento existente = new SessaoTratamento();
        existente.setAssistido(assistido);
        existente.setDataConsulta(DOMINGO);
        when(sessaoRepository.findByDataConsulta(DOMINGO)).thenReturn(java.util.List.of(existente));

        CheckinService.ResultadoAutoCheckin resultado = checkinService.registrarAutoCheckin(10L, codigo, assistido);

        assertTrue(resultado.jaEstavaPresente());
        verify(tratamentoService, never()).registrarSessao(any(), anyBoolean());
    }

    @Test
    void semJanelaAbertaOQrNaoCarimbaPresenca() {
        when(sessaoAssistenciaRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.empty());

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> checkinService.registrarPresenca("codigo-do-cartao"));

        assertTrue(erro.getMessage().contains("Nenhuma sessão está aberta"));
        verify(tratamentoService, never()).registrarSessao(any(), anyBoolean());
    }

    @Test
    void presencaPeloQrUsaADataDaSessaoAbertaEAPrelecaoDaMesmaData() {
        comSessaoAberta();
        when(prelecaoRepository.findByDataApresentacao(DOMINGO)).thenReturn(Optional.of(prelecaoDeDomingo));
        when(assistidoRepository.findByCodigoCartao("codigo-do-cartao")).thenReturn(Optional.of(assistido));
        when(tratamentoService.registrarSessao(any(SessaoTratamento.class), eq(false)))
                .thenAnswer(i -> new TratamentoService.ResultadoSessao(i.getArgument(0), false, false));

        checkinService.registrarPresenca("codigo-do-cartao");

        ArgumentCaptor<SessaoTratamento> captor = ArgumentCaptor.forClass(SessaoTratamento.class);
        verify(tratamentoService).registrarSessao(captor.capture(), eq(false));
        assertEquals(DOMINGO, captor.getValue().getDataConsulta());
        assertEquals(prelecaoDeDomingo, captor.getValue().getPrelecao());
        assertEquals(assistido, captor.getValue().getAssistido());
    }

    // Decisão 8.1: a sessão não depende mais da escala de preleções — sem preleção na data, a
    // presença entra do mesmo jeito, só sem preleção vinculada.
    @Test
    void sessaoSemPrelecaoCadastradaAindaCarimbaPresenca() {
        comSessaoAberta();
        when(prelecaoRepository.findByDataApresentacao(DOMINGO)).thenReturn(Optional.empty());
        when(assistidoRepository.findByCodigoCartao("codigo-do-cartao")).thenReturn(Optional.of(assistido));
        when(tratamentoService.registrarSessao(any(SessaoTratamento.class), eq(false)))
                .thenAnswer(i -> new TratamentoService.ResultadoSessao(i.getArgument(0), false, false));

        CheckinService.ResultadoCheckin resultado = checkinService.registrarPresenca("codigo-do-cartao");

        assertEquals(DOMINGO, resultado.presenca().getDataConsulta());
        assertNull(resultado.presenca().getPrelecao());
    }

    @Test
    void presencaPelaBuscaExigeOCheckinAbertoDaquelaSessao() {
        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> checkinService.registrarPresenca(10L, 5L, false));

        assertTrue(erro.getMessage().contains("não está aberta"));
        verify(tratamentoService, never()).registrarSessao(any(), anyBoolean());
    }

    @Test
    void presencaPelaBuscaRepassaAConfirmacaoDeReinicio() {
        sessaoDeDomingo.setCheckinAbertoEm(LocalDateTime.now());
        when(sessaoAssistenciaRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));
        when(assistidoRepository.findById(5L)).thenReturn(Optional.of(assistido));
        when(tratamentoService.registrarSessao(any(SessaoTratamento.class), eq(true)))
                .thenAnswer(i -> new TratamentoService.ResultadoSessao(i.getArgument(0), true, false));

        CheckinService.ResultadoCheckin resultado = checkinService.registrarPresenca(10L, 5L, true);

        assertTrue(resultado.tratamentoReiniciado());
        assertEquals(sessaoDeDomingo, resultado.sessao());
    }

    // Saída prevista para quem comparece num dia que não é o dele: não passa pelas regras do cartão.
    @Test
    void ouvinteExplicitoNaoAvancaOCartaoNemPassaPeloTratamentoService() {
        comSessaoAberta();
        when(assistidoRepository.findByCodigoCartao("codigo-do-cartao")).thenReturn(Optional.of(assistido));
        when(sessaoRepository.save(any(SessaoTratamento.class))).thenAnswer(i -> i.getArgument(0));

        CheckinService.ResultadoCheckin resultado = checkinService.registrarOuvinte("codigo-do-cartao");

        assertTrue(resultado.ouvinte());
        assertTrue(resultado.presenca().isOuvinte());
        assertNull(resultado.presenca().getNumeroSerie());
        verify(tratamentoService, never()).registrarSessao(any(), anyBoolean());
    }

    @Test
    void cartaoDeAssistidoInativoNaoCarimbaPresenca() {
        comSessaoAberta();
        assistido.setAtivo(false);
        when(assistidoRepository.findByCodigoCartao("codigo-do-cartao")).thenReturn(Optional.of(assistido));

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> checkinService.registrarPresenca("codigo-do-cartao"));

        assertTrue(erro.getMessage().contains("inativo"));
        verify(tratamentoService, never()).registrarSessao(any(), anyBoolean());
    }

    @Test
    void qrDesconhecidoNaoResolveCartao() {
        comSessaoAberta();
        when(assistidoRepository.findByCodigoCartao("nao-existe")).thenReturn(Optional.empty());

        assertThrows(RegraNegocioException.class, () -> checkinService.registrarPresenca("nao-existe"));
    }

    @Test
    void codigoDoCartaoEhGeradoUmaVezESoQuandoFalta() {
        assistido.setCodigoCartao(null);
        when(assistidoRepository.save(assistido)).thenReturn(assistido);

        String gerado = checkinService.garantirCodigoCartao(assistido);
        assertNotNull(gerado);

        String segundaChamada = checkinService.garantirCodigoCartao(assistido);
        assertEquals(gerado, segundaChamada);
        verify(assistidoRepository).save(assistido);
    }
}
