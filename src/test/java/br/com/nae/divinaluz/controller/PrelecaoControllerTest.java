package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import br.com.nae.divinaluz.service.CheckinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrelecaoControllerTest {

    @Mock
    private PrelecaoRepository prelecaoRepository;

    @Mock
    private TrabalhadorRepository trabalhadorRepository;

    @Mock
    private AssistidoRepository assistidoRepository;

    @Mock
    private SessaoRepository sessaoRepository;

    @Mock
    private CheckinService checkinService;

    @InjectMocks
    private PrelecaoController prelecaoController;

    private static final LocalDate DOMINGO = LocalDate.of(2026, 10, 11);

    private Trabalhador preletor;
    private Assistido convidado;

    @BeforeEach
    void setUp() {
        Assistido assistido = new Assistido();
        assistido.setNome("Preletor Teste");

        preletor = new Trabalhador();
        preletor.setId(10L);
        preletor.setAssistido(assistido);
        preletor.setFuncoes(Set.of(TipoTrabalhador.EXPOSITOR_PRELETOR));

        // Convidado (V41): um cadastro com vínculo CONVIDADO, feito no Novo Cadastro.
        convidado = new Assistido();
        convidado.setId(20L);
        convidado.setNome("Divaldo Convidado");
        convidado.setVinculo(Assistido.VINCULO_CONVIDADO);

        lenient().when(trabalhadorRepository.findById(10L)).thenReturn(Optional.of(preletor));
        lenient().when(assistidoRepository.findById(20L)).thenReturn(Optional.of(convidado));
        lenient().when(prelecaoRepository.findByDataApresentacaoAndCanceladaEmIsNull(any())).thenReturn(Optional.empty());
    }

    private Prelecao prelecao(LocalDate data, String tema) {
        Prelecao prelecao = new Prelecao();
        prelecao.setDataApresentacao(data);
        prelecao.setTema(tema);
        return prelecao;
    }

    @Test
    void deveBloquearPrelecaoEmDiaNaoPermitido() {
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        ExtendedModelMap model = new ExtendedModelMap();

        String resultado = prelecaoController.salvarPrelecao(prelecao(LocalDate.of(2026, 9, 23), "Tema teste"),
                "T:10", redirectAttributes, model);

        assertEquals("prelecao-form", resultado);
        assertEquals("Tema teste", ((Prelecao) model.get("prelecao")).getTema());
        assertEquals(preletor, ((Prelecao) model.get("prelecao")).getPreletor());
        assertEquals("T:10", model.get("preletorEscolhido"), "o select volta com o preletor escolhido");
        assertNull(((Prelecao) model.get("prelecao")).getDataApresentacao());
        assertNotNull(redirectAttributes.getFlashAttributes().get("erro"));
    }

    @Test
    void deveBloquearMaisDeUmaPrelecaoNaMesmaData() {
        Prelecao existente = prelecao(LocalDate.of(2026, 9, 22), "Outra");
        existente.setId(99L);
        when(prelecaoRepository.findByDataApresentacaoAndCanceladaEmIsNull(LocalDate.of(2026, 9, 22)))
                .thenReturn(Optional.of(existente));

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        ExtendedModelMap model = new ExtendedModelMap();

        String resultado = prelecaoController.salvarPrelecao(prelecao(LocalDate.of(2026, 9, 22), "Tema 2"),
                "T:10", redirectAttributes, model);

        assertEquals("prelecao-form", resultado);
        assertEquals("Tema 2", ((Prelecao) model.get("prelecao")).getTema());
        assertNotNull(redirectAttributes.getFlashAttributes().get("erro"));
        verify(prelecaoRepository, never()).save(any());
    }

    @Test
    void preletorConvidadoEntraNaEscalaSemSerTrabalhador() {
        String resultado = prelecaoController.salvarPrelecao(prelecao(DOMINGO, "Caridade"),
                "C:20", new RedirectAttributesModelMap(), new ExtendedModelMap());

        assertEquals("redirect:/prelecao", resultado);
        ArgumentCaptor<Prelecao> salva = ArgumentCaptor.forClass(Prelecao.class);
        verify(prelecaoRepository).save(salva.capture());
        assertSame(convidado, salva.getValue().getConvidado());
        assertNull(salva.getValue().getPreletor());
        assertEquals("Divaldo Convidado", salva.getValue().getNomePreletor());
        assertTrue(salva.getValue().isPreletorConvidado());
    }

    @Test
    void cadastroQueNaoEhConvidadoNaoPodeSerPreletorConvidado() {
        convidado.setVinculo("ASSISTIDO");
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        prelecaoController.salvarPrelecao(prelecao(DOMINGO, "Caridade"), "C:20", redirectAttributes,
                new ExtendedModelMap());

        assertNotNull(redirectAttributes.getFlashAttributes().get("erro"));
        verify(prelecaoRepository, never()).save(any());
    }

    @Test
    void cancelarMantemNaEscalaEReativarRespeitaAData() {
        Prelecao cancelada = prelecao(DOMINGO, "Fé");
        cancelada.setId(5L);
        cancelada.setPreletor(preletor);
        when(prelecaoRepository.findById(5L)).thenReturn(Optional.of(cancelada));

        prelecaoController.cancelarPrelecao(5L, "Preletor adoeceu", new RedirectAttributesModelMap());
        assertTrue(cancelada.isCancelada());
        assertEquals("Preletor adoeceu", cancelada.getMotivoCancelamento());
        verify(prelecaoRepository, never()).deleteById(any());

        // Outra preleção ocupou a data enquanto esta estava cancelada: não dá para reativar.
        Prelecao outra = prelecao(DOMINGO, "Outra");
        outra.setId(6L);
        when(prelecaoRepository.findByDataApresentacaoAndCanceladaEmIsNull(DOMINGO)).thenReturn(Optional.of(outra));
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        prelecaoController.reativarPrelecao(5L, redirectAttributes);
        assertTrue(cancelada.isCancelada());
        assertNotNull(redirectAttributes.getFlashAttributes().get("erro"));

        when(prelecaoRepository.findByDataApresentacaoAndCanceladaEmIsNull(DOMINGO)).thenReturn(Optional.empty());
        prelecaoController.reativarPrelecao(5L, new RedirectAttributesModelMap());
        assertFalse(cancelada.isCancelada());
        assertNull(cancelada.getMotivoCancelamento());
    }

    @Test
    void naoExcluiPrelecaoQueJaTemPresencas() {
        when(sessaoRepository.existsByPrelecaoId(5L)).thenReturn(true);
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        prelecaoController.excluirPrelecao(5L, redirectAttributes);

        verify(prelecaoRepository, never()).deleteById(any());
        assertTrue(((String) redirectAttributes.getFlashAttributes().get("erro")).contains("Cancelar"));
    }
}
