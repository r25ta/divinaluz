package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.springframework.ui.ExtendedModelMap;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrelecaoControllerTest {

    @Mock
    private PrelecaoRepository prelecaoRepository;

    @Mock
    private TrabalhadorRepository trabalhadorRepository;

    @InjectMocks
    private PrelecaoController prelecaoController;

    private Long preletorId;
    private Trabalhador preletor;

    @BeforeEach
    void setUp() {
        Assistido assistido = new Assistido();
        assistido.setNome("Preletor Teste");

        preletor = new Trabalhador();
        preletor.setId(10L);
        preletor.setAssistido(assistido);
        preletor.setFuncoes(Set.of(TipoTrabalhador.EXPOSITOR_PRELETOR));
        preletorId = preletor.getId();

        when(trabalhadorRepository.findById(preletorId)).thenReturn(Optional.of(preletor));
    }

    @Test
    void deveBloquearPrelecaoEmDiaNaoPermitido() {
        Prelecao prelecao = new Prelecao();
        prelecao.setDataApresentacao(LocalDate.of(2026, 9, 23));
        prelecao.setTema("Tema teste");

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        ExtendedModelMap model = new ExtendedModelMap();

        String resultado = prelecaoController.salvarPrelecao(prelecao, preletorId, redirectAttributes, model);

        assertEquals("prelecao-form", resultado);
        assertEquals("Tema teste", ((Prelecao) model.get("prelecao")).getTema());
        assertEquals(preletor, ((Prelecao) model.get("prelecao")).getPreletor());
        assertEquals(null, ((Prelecao) model.get("prelecao")).getDataApresentacao());
        assertNotNull(redirectAttributes.getFlashAttributes().get("erro"));
    }

    @Test
    void deveBloquearMaisDeUmaPrelecaoNaMesmaData() {
        Prelecao prelecaoExistente = new Prelecao();
        prelecaoExistente.setId(99L);
        prelecaoExistente.setDataApresentacao(LocalDate.of(2026, 9, 22));

        when(prelecaoRepository.findByDataApresentacao(LocalDate.of(2026, 9, 22)))
                .thenReturn(Optional.of(prelecaoExistente));

        Prelecao novaPrelecao = new Prelecao();
        novaPrelecao.setDataApresentacao(LocalDate.of(2026, 9, 22));
        novaPrelecao.setTema("Tema 2");

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        ExtendedModelMap model = new ExtendedModelMap();

        String resultado = prelecaoController.salvarPrelecao(novaPrelecao, preletorId, redirectAttributes, model);

        assertEquals("prelecao-form", resultado);
        assertEquals("Tema 2", ((Prelecao) model.get("prelecao")).getTema());
        assertEquals(preletor, ((Prelecao) model.get("prelecao")).getPreletor());
        assertEquals(null, ((Prelecao) model.get("prelecao")).getDataApresentacao());
        assertNotNull(redirectAttributes.getFlashAttributes().get("erro"));
    }
}
