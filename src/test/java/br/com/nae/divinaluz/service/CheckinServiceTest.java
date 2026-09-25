package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckinServiceTest {

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

    private Prelecao sessaoDeDomingo;
    private Assistido assistido;

    @BeforeEach
    void setUp() {
        sessaoDeDomingo = new Prelecao();
        sessaoDeDomingo.setId(10L);
        sessaoDeDomingo.setTema("Evangelho no Lar");
        sessaoDeDomingo.setDataApresentacao(DOMINGO);

        assistido = new Assistido();
        assistido.setId(5L);
        assistido.setNome("Maria");
        assistido.setCodigoCartao("codigo-do-cartao");
    }

    @Test
    void checkinSoAbreNaDataDaSessao() {
        when(prelecaoRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> checkinService.abrirCheckin(10L, DOMINGO.minusDays(2)));

        assertTrue(erro.getMessage().contains("27/09/2026"));
        verify(prelecaoRepository, never()).save(any());
    }

    @Test
    void abrirCheckinNaDataMarcaAJanelaComoAberta() {
        when(prelecaoRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));
        when(prelecaoRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.empty());
        when(prelecaoRepository.save(any(Prelecao.class))).thenAnswer(i -> i.getArgument(0));

        Prelecao aberta = checkinService.abrirCheckin(10L, DOMINGO);

        assertNotNull(aberta.getCheckinAbertoEm());
        assertNull(aberta.getCheckinFechadoEm());
        assertTrue(aberta.isCheckinAberto());
    }

    // Uma janela esquecida aberta em outra data travaria o check-in de hoje, então ela é fechada.
    @Test
    void abrirCheckinFechaJanelaEsquecidaDeOutraData() {
        Prelecao esquecida = new Prelecao();
        esquecida.setId(9L);
        esquecida.setDataApresentacao(DOMINGO.minusWeeks(1));
        esquecida.setCheckinAbertoEm(LocalDateTime.now().minusDays(7));

        when(prelecaoRepository.findById(10L)).thenReturn(Optional.of(sessaoDeDomingo));
        when(prelecaoRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.of(esquecida));
        when(prelecaoRepository.save(any(Prelecao.class))).thenAnswer(i -> i.getArgument(0));

        checkinService.abrirCheckin(10L, DOMINGO);

        assertNotNull(esquecida.getCheckinFechadoEm(), "a janela antiga deveria ter sido fechada");
    }

    @Test
    void semJanelaAbertaOQrNaoCarimbaPresenca() {
        when(prelecaoRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.empty());

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> checkinService.registrarPresenca("codigo-do-cartao"));

        assertTrue(erro.getMessage().contains("check-in aberto"));
        verify(tratamentoService, never()).registrarSessao(any());
    }

    @Test
    void presencaPeloQrUsaADataEAPrelecaoDaSessaoAberta() {
        sessaoDeDomingo.setCheckinAbertoEm(LocalDateTime.now());
        when(prelecaoRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.of(sessaoDeDomingo));
        when(assistidoRepository.findByCodigoCartao("codigo-do-cartao")).thenReturn(Optional.of(assistido));
        when(tratamentoService.registrarSessao(any(SessaoTratamento.class)))
                .thenAnswer(i -> new TratamentoService.ResultadoSessao(i.getArgument(0), false, false));

        checkinService.registrarPresenca("codigo-do-cartao");

        ArgumentCaptor<SessaoTratamento> captor = ArgumentCaptor.forClass(SessaoTratamento.class);
        verify(tratamentoService).registrarSessao(captor.capture());
        assertEquals(DOMINGO, captor.getValue().getDataConsulta());
        assertEquals(sessaoDeDomingo, captor.getValue().getPrelecao());
        assertEquals(assistido, captor.getValue().getAssistido());
    }

    // Saída prevista para quem comparece num dia que não é o dele: não passa pelas regras do cartão.
    @Test
    void ouvinteExplicitoNaoAvancaOCartaoNemPassaPeloTratamentoService() {
        sessaoDeDomingo.setCheckinAbertoEm(LocalDateTime.now());
        when(prelecaoRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.of(sessaoDeDomingo));
        when(assistidoRepository.findByCodigoCartao("codigo-do-cartao")).thenReturn(Optional.of(assistido));
        when(sessaoRepository.save(any(SessaoTratamento.class))).thenAnswer(i -> i.getArgument(0));

        CheckinService.ResultadoCheckin resultado = checkinService.registrarOuvinte("codigo-do-cartao");

        assertTrue(resultado.ouvinte());
        assertTrue(resultado.sessao().isOuvinte());
        assertNull(resultado.sessao().getNumeroSerie());
        verify(tratamentoService, never()).registrarSessao(any());
    }

    @Test
    void cartaoDeAssistidoInativoNaoCarimbaPresenca() {
        sessaoDeDomingo.setCheckinAbertoEm(LocalDateTime.now());
        assistido.setAtivo(false);
        when(prelecaoRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.of(sessaoDeDomingo));
        when(assistidoRepository.findByCodigoCartao("codigo-do-cartao")).thenReturn(Optional.of(assistido));

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> checkinService.registrarPresenca("codigo-do-cartao"));

        assertTrue(erro.getMessage().contains("inativo"));
        verify(tratamentoService, never()).registrarSessao(any());
    }

    @Test
    void qrDesconhecidoNaoResolveCartao() {
        sessaoDeDomingo.setCheckinAbertoEm(LocalDateTime.now());
        when(prelecaoRepository.findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull())
                .thenReturn(Optional.of(sessaoDeDomingo));
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
