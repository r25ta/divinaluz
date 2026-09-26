package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.EscalaSessao;
import br.com.nae.divinaluz.model.PosicaoSessao;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.EntrevistaRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoAssistenciaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessaoServiceTest {

    @Mock
    private SessaoAssistenciaRepository sessaoAssistenciaRepository;

    @Mock
    private PrelecaoRepository prelecaoRepository;

    @Mock
    private TrabalhadorRepository trabalhadorRepository;

    @Mock
    private SessaoRepository sessaoRepository;

    @Mock
    private EntrevistaRepository entrevistaRepository;

    @Mock
    private AssistidoRepository assistidoRepository;

    @Mock
    private TipoTratamentoRepository tipoTratamentoRepository;

    @Mock
    private TratamentoService tratamentoService;

    @Mock
    private CheckinService checkinService;

    @InjectMocks
    private SessaoService sessaoService;

    private static final LocalDate DOMINGO = LocalDate.of(2026, 9, 27);

    private SessaoAssistencia sessao;
    private TipoTratamento p1;
    private TipoTratamento p2;
    private TipoTratamento ch;

    @BeforeEach
    void setUp() {
        sessao = new SessaoAssistencia();
        sessao.setId(1L);
        sessao.setData(DOMINGO);
        sessao.setDiaFrequencia(DiaFrequencia.DOMINGO_08H);

        p1 = tratamento(1L, "P1");
        p2 = tratamento(2L, "P2");
        ch = tratamento(8L, "CH");
    }

    // ------------------------------------------------------------ sessão

    @Test
    void sessaoSoExisteNoDomingoOuNaTerca() {
        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> sessaoService.garantirSessao(DOMINGO.plusDays(1)));

        assertTrue(erro.getMessage().contains("Domingos"));
        verify(sessaoAssistenciaRepository, never()).save(any());
    }

    @Test
    void garantirSessaoCriaComODiaDaDataEReaproveitaAExistente() {
        LocalDate terca = DOMINGO.plusDays(2);
        when(sessaoAssistenciaRepository.findByData(terca)).thenReturn(Optional.empty());
        when(sessaoAssistenciaRepository.save(any(SessaoAssistencia.class))).thenAnswer(i -> i.getArgument(0));

        SessaoAssistencia nova = sessaoService.garantirSessao(terca);

        assertEquals(terca, nova.getData());
        assertEquals(DiaFrequencia.TERCA_19H, nova.getDiaFrequencia());

        when(sessaoAssistenciaRepository.findByData(DOMINGO)).thenReturn(Optional.of(sessao));
        assertSame(sessao, sessaoService.garantirSessao(DOMINGO));
        verify(sessaoAssistenciaRepository).save(any());
    }

    @Test
    void proximaDataDeSessaoEhHojeNoDomingoOuATercaSeguinte() {
        assertEquals(DOMINGO, SessaoService.proximaDataDeSessao(DOMINGO));
        assertEquals(DOMINGO.plusDays(2), SessaoService.proximaDataDeSessao(DOMINGO.plusDays(1)));
        assertEquals(DOMINGO.plusDays(7), SessaoService.proximaDataDeSessao(DOMINGO.plusDays(3)));
    }

    // ------------------------------------------------------------ escala

    @Test
    void escalarAdicionaOTrabalhadorNaPosicao() {
        Trabalhador joseli = trabalhador(20L, "Joseli");
        when(sessaoAssistenciaRepository.findById(1L)).thenReturn(Optional.of(sessao));
        when(trabalhadorRepository.findById(20L)).thenReturn(Optional.of(joseli));

        sessaoService.escalar(1L, PosicaoSessao.CAMARA_PASSE, 20L);

        assertEquals(1, sessao.getEscala().size());
        assertEquals(PosicaoSessao.CAMARA_PASSE, sessao.getEscala().get(0).getPosicao());
        assertSame(joseli, sessao.getEscala().get(0).getTrabalhador());
    }

    @Test
    void mesmoTrabalhadorNaoEntraDuasVezesNaMesmaPosicaoMasPodeOcuparOutra() {
        Trabalhador sueli = trabalhador(21L, "Sueli");
        escalar(sueli, PosicaoSessao.PASSE_LIMPEZA);
        when(sessaoAssistenciaRepository.findById(1L)).thenReturn(Optional.of(sessao));
        when(trabalhadorRepository.findById(21L)).thenReturn(Optional.of(sueli));

        assertThrows(RegraNegocioException.class, () -> sessaoService.escalar(1L, PosicaoSessao.PASSE_LIMPEZA, 21L));

        sessaoService.escalar(1L, PosicaoSessao.P3B, 21L);
        assertEquals(2, sessao.getEscala().size());
    }

    // O mínimo só avisa: a tela mostra quantos faltam, nada é bloqueado.
    @Test
    void quadroDaEscalaMostraQuantosFaltamParaOMinimo() {
        escalar(trabalhador(20L, "Joseli"), PosicaoSessao.CAMARA_PASSE);
        escalar(trabalhador(22L, "Alaide"), PosicaoSessao.CAMARA_PASSE);
        escalar(trabalhador(23L, "Jose"), PosicaoSessao.DIRIGENTE);

        Map<PosicaoSessao, SessaoService.QuadroPosicao> quadro = sessaoService.quadroEscala(sessao).stream()
                .collect(java.util.stream.Collectors.toMap(SessaoService.QuadroPosicao::posicao, q -> q));

        assertEquals(PosicaoSessao.values().length, quadro.size());
        assertEquals(3, quadro.get(PosicaoSessao.CAMARA_PASSE).faltam());
        assertEquals(0, quadro.get(PosicaoSessao.DIRIGENTE).faltam());
        assertEquals(2, quadro.get(PosicaoSessao.RECEPCIONISTA).faltam());
        assertEquals(0, quadro.get(PosicaoSessao.SECRETARIA).faltam(), "posição sem mínimo nunca falta");
        assertEquals(List.of("Alaide", "Joseli"), quadro.get(PosicaoSessao.CAMARA_PASSE).escalados().stream()
                .map(e -> e.getTrabalhador().getAssistido().getNome()).toList());
    }

    // ------------------------------------------------------------ preletor

    @Test
    void preletorDaEscalaValeAteUmaTrocaEmergencial() {
        Prelecao daEscala = new Prelecao();
        daEscala.setDataApresentacao(DOMINGO);
        daEscala.setPreletor(trabalhador(30L, "Paulo"));
        daEscala.setTema("Evangelho no Lar");
        when(prelecaoRepository.findByDataApresentacao(DOMINGO)).thenReturn(Optional.of(daEscala));

        SessaoService.PreletorDaSessao semTroca = sessaoService.preletorDaSessao(sessao);
        assertEquals("Paulo", semTroca.preletor().getAssistido().getNome());
        assertEquals("Evangelho no Lar", semTroca.tema());
        assertFalse(semTroca.substituido());

        sessao.setPreletorSubstituto(trabalhador(31L, "Kalvin"));
        SessaoService.PreletorDaSessao comTroca = sessaoService.preletorDaSessao(sessao);
        assertEquals("Kalvin", comTroca.preletor().getAssistido().getNome());
        assertEquals("Evangelho no Lar", comTroca.tema(), "sem tema novo, mantém o da escala");
        assertTrue(comTroca.substituido());
        assertSame(daEscala, comTroca.prelecaoDaEscala());
    }

    @Test
    void trocaSemPreletorESemTemaVoltaAValerAEscala() {
        sessao.setPreletorSubstituto(trabalhador(31L, "Kalvin"));
        sessao.setTemaSubstituto("Amar e Perdoar");
        when(sessaoAssistenciaRepository.findById(1L)).thenReturn(Optional.of(sessao));
        when(sessaoAssistenciaRepository.save(sessao)).thenReturn(sessao);

        sessaoService.trocarPreletor(1L, null, "  ");

        assertEquals(null, sessao.getPreletorSubstituto());
        assertEquals(null, sessao.getTemaSubstituto());
    }

    // ------------------------------------------------------------ indicadores

    @Test
    void indicadoresContamPessoasPorTratamentoOuvintesNovosETrabalhadores() {
        Assistido ana = assistido(1L, "Ana", ch, "ASSISTIDO");
        Assistido bruno = assistido(2L, "Bruno", p2, "TRABALHADOR");
        Assistido carla = assistido(3L, "Carla", p2, "ASSISTIDO");
        Assistido davi = assistido(4L, "Davi", p1, "ASSISTIDO");
        Assistido eva = assistido(5L, "Eva", p2, "ASSISTIDO");

        when(sessaoRepository.findByDataConsulta(DOMINGO)).thenReturn(List.of(
                presenca(ana, false, 2),
                presenca(bruno, false, 3),
                presenca(carla, false, 1),
                presenca(davi, true, null),          // ouvinte (dia que não é o dele)
                presenca(eva, false, 1),
                presenca(eva, true, null)));         // QR escaneado de novo: a mesma pessoa, uma vez só
        when(sessaoRepository.findAssistidosComPresencaAntesDe(eq(DOMINGO), any()))
                .thenReturn(Set.of(1L, 2L, 4L, 5L)); // Carla nunca tinha vindo: é "nova"
        when(entrevistaRepository.countByData(DOMINGO)).thenReturn(3L);
        when(tipoTratamentoRepository.findAll()).thenReturn(List.of(p1, p2, ch));

        SessaoService.Indicadores ind = sessaoService.indicadores(sessao);

        assertEquals(5, ind.totalPresentes());
        assertEquals(4, ind.efetivas());
        assertEquals(1, ind.ouvintes());
        assertEquals(List.of("P2", "CH"), List.copyOf(ind.porTratamento().keySet()), "ordem do catálogo, sem zeros");
        assertEquals(3L, ind.porTratamento().get("P2"));
        assertEquals(1L, ind.porTratamento().get("CH"));
        assertFalse(ind.porTratamento().containsKey("P1"), "Davi foi ouvinte: não conta no tratamento");
        assertEquals(1, ind.trabalhadoresPresentes());
        assertEquals(1, ind.novos());
        assertEquals(3, ind.retornos());

        SessaoService.Presente presencaEva = ind.presentes().stream()
                .filter(p -> p.assistido().getId().equals(5L)).findFirst().orElseThrow();
        assertFalse(presencaEva.ouvinte(), "efetiva prevalece sobre a linha de ouvinte");
        assertEquals(1, presencaEva.numeroSerie());
    }

    @Test
    void indicadoresDeSessaoVaziaNaoConsultamPresencaAnterior() {
        when(sessaoRepository.findByDataConsulta(DOMINGO)).thenReturn(List.of());
        when(tipoTratamentoRepository.findAll()).thenReturn(List.of(p1, p2, ch));

        SessaoService.Indicadores ind = sessaoService.indicadores(sessao);

        assertEquals(0, ind.totalPresentes());
        assertTrue(ind.porTratamento().isEmpty());
        verify(sessaoRepository, never()).findAssistidosComPresencaAntesDe(any(), any());
    }

    // ------------------------------------------------------------ recepção

    @Test
    void buscaPorNomeIgnoraAcentoEMaiusculas() {
        Assistido joao = assistido(1L, "João Conceição", p2, "ASSISTIDO");
        Assistido maria = assistido(2L, "Maria", p2, "ASSISTIDO");
        when(assistidoRepository.findByAtivo(true)).thenReturn(List.of(maria, joao));

        assertEquals(List.of(joao), sessaoService.buscarAssistidos("  CONCEICAO "));
        assertEquals(List.of(), sessaoService.buscarAssistidos("j"), "menos de 2 letras não busca");
    }

    @Test
    void cadastroRapidoEntraEmP2ComODiaEAPresencaDaSessao() {
        when(checkinService.exigirCheckinAberto(1L)).thenReturn(sessao);
        when(assistidoRepository.findByAtivo(true)).thenReturn(List.of());
        when(assistidoRepository.save(any(Assistido.class))).thenAnswer(i -> i.getArgument(0));
        when(tipoTratamentoRepository.findByCodigo("P2")).thenReturn(Optional.of(p2));

        Assistido novo = sessaoService.cadastroRapido(1L, "  Rosa   Maria ", LocalDate.of(1980, 1, 2), "F", false);

        assertEquals("Rosa Maria", novo.getNome());
        assertEquals("ASSISTIDO", novo.getVinculo());
        verify(tratamentoService).alterarDiaFrequencia(eq(novo), eq(DiaFrequencia.DOMINGO_08H), anyString());
        verify(tratamentoService).iniciarTratamentoInicial(novo, p2, DOMINGO);
        verify(checkinService).garantirCodigoCartao(novo);
    }

    @Test
    void cadastroRapidoDeNomeJaCadastradoPedeConfirmacao() {
        when(checkinService.exigirCheckinAberto(1L)).thenReturn(sessao);
        when(assistidoRepository.findByAtivo(true)).thenReturn(List.of(assistido(9L, "José da Silva", p2, "ASSISTIDO")));

        assertThrows(SessaoService.HomonimoException.class,
                () -> sessaoService.cadastroRapido(1L, "jose da silva", null, null, false));
        verify(assistidoRepository, never()).save(any());

        when(assistidoRepository.save(any(Assistido.class))).thenAnswer(i -> i.getArgument(0));
        when(tipoTratamentoRepository.findByCodigo("P2")).thenReturn(Optional.of(p2));
        sessaoService.cadastroRapido(1L, "jose da silva", null, null, true);
        verify(tratamentoService).iniciarTratamentoInicial(any(), eq(p2), eq(DOMINGO));
    }

    @Test
    void cadastroRapidoSemCheckinAbertoNaoCadastra() {
        when(checkinService.exigirCheckinAberto(1L)).thenThrow(new RegraNegocioException("não está aberto"));

        assertThrows(RegraNegocioException.class,
                () -> sessaoService.cadastroRapido(1L, "Rosa", null, null, false));
        verify(assistidoRepository, never()).save(any());
    }

    // Exemplo do item 6: assistido de domingo pede para passar para terça. O dia muda (com
    // histórico) e a presença segue para o CheckinService — onde a regra semanal decide se é
    // efetiva ou ouvinte.
    @Test
    void mudarDiaRegistraHistoricoEMarcaPresencaNaSessao() {
        SessaoAssistencia terca = new SessaoAssistencia();
        terca.setId(2L);
        terca.setData(DOMINGO.plusDays(2));
        terca.setDiaFrequencia(DiaFrequencia.TERCA_19H);
        Assistido ana = assistido(1L, "Ana", p2, "ASSISTIDO");
        ana.setDiaFrequencia(DiaFrequencia.DOMINGO_08H);
        when(checkinService.exigirCheckinAberto(2L)).thenReturn(terca);
        when(assistidoRepository.findById(1L)).thenReturn(Optional.of(ana));

        sessaoService.mudarDiaEMarcarPresenca(2L, 1L);

        verify(tratamentoService).alterarDiaFrequencia(eq(ana), eq(DiaFrequencia.TERCA_19H), anyString());
        verify(checkinService).registrarPresenca(2L, 1L, false);
    }

    // ------------------------------------------------------------ utilitários

    private void escalar(Trabalhador trabalhador, PosicaoSessao posicao) {
        EscalaSessao escala = new EscalaSessao();
        escala.setId((long) sessao.getEscala().size() + 100);
        escala.setSessao(sessao);
        escala.setTrabalhador(trabalhador);
        escala.setPosicao(posicao);
        sessao.getEscala().add(escala);
    }

    private static TipoTratamento tratamento(Long id, String codigo) {
        TipoTratamento t = new TipoTratamento();
        t.setId(id);
        t.setCodigo(codigo);
        return t;
    }

    private static Trabalhador trabalhador(Long id, String nome) {
        Trabalhador t = new Trabalhador();
        t.setId(id);
        t.setAssistido(assistido(id + 1000, nome, null, "TRABALHADOR"));
        return t;
    }

    private static Assistido assistido(Long id, String nome, TipoTratamento tratamento, String vinculo) {
        Assistido a = new Assistido();
        a.setId(id);
        a.setNome(nome);
        a.setTratamentoAtual(tratamento);
        a.setVinculo(vinculo);
        return a;
    }

    private static SessaoTratamento presenca(Assistido assistido, boolean ouvinte, Integer numeroSerie) {
        SessaoTratamento s = new SessaoTratamento();
        s.setAssistido(assistido);
        s.setDataConsulta(DOMINGO);
        s.setOuvinte(ouvinte);
        s.setNumeroSerie(numeroSerie);
        return s;
    }
}
