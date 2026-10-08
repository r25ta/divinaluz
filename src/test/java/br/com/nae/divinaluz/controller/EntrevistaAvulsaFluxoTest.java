package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.Entrevista;
import br.com.nae.divinaluz.model.TipoEntrevista;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.EntrevistaRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.service.TratamentoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ponta a ponta das entrevistas avulsas (V43): sobe a aplicação contra o banco (como o contextLoads),
 * entra como o admin das migrations e percorre as telas — prontuário, formulário, gravação e cartão.
 * Roda numa transação desfeita no fim, sem deixar nada no banco.
 */
@SpringBootTest
@Transactional
class EntrevistaAvulsaFluxoTest {

    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private AssistidoRepository assistidoRepository;
    @Autowired
    private EntrevistaRepository entrevistaRepository;
    @Autowired
    private TipoTratamentoRepository tipoTratamentoRepository;
    @Autowired
    private TratamentoService tratamentoService;

    private MockMvc mvc;
    private UserDetails admin;
    private Assistido assistido;
    private LocalDate primeiraSessao;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = userDetailsService.loadUserByUsername("admin");

        primeiraSessao = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        assistido = new Assistido();
        assistido.setNome("Teste Entrevista Avulsa");
        assistido.setVinculo("ASSISTIDO");
        assistido.setDiaFrequencia(DiaFrequencia.DOMINGO_08H);
        assistidoRepository.save(assistido);
        tratamentoService.iniciarTratamentoInicial(assistido,
                tipoTratamentoRepository.findByCodigo("P2").orElseThrow(), primeiraSessao);
    }

    @Test
    void entrevistaDaPrimeiraSessaoEExcepcionalPelasTelas() throws Exception {
        String id = assistido.getId().toString();
        String data = primeiraSessao.format(BR);

        mvc.perform(get("/prontuario/" + id).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Outras Entrevistas")))
                .andExpect(content().string(containsString("Entrevista da 1ª Sessão (")))
                .andExpect(content().string(containsString(">" + data + "</span>)")));

        mvc.perform(get("/prontuario/" + id + "/nova-entrevista-avulsa")
                        .param("tipo", "PRIMEIRA_SESSAO").param("data", data).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Registrar Entrevista da 1ª sessão")));

        mvc.perform(post("/prontuario/" + id + "/entrevista-avulsa").param("tipo", "PRIMEIRA_SESSAO")
                        .param("data", data).param("observacoes", "Acolhimento e explicação do P2.")
                        .with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/prontuario/" + id));

        // A excepcional tem data livre: uma quarta-feira, fora do dia de assistência.
        LocalDate quarta = primeiraSessao.minusDays(4);
        mvc.perform(post("/prontuario/" + id + "/entrevista-avulsa").param("tipo", "EXCEPCIONAL")
                        .param("data", quarta.format(BR)).param("observacoes", "Pediu para conversar sobre o trabalho.")
                        .with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<Entrevista> gravadas = entrevistaRepository.findByAssistidoIdOrderByDataDesc(assistido.getId());
        assertEquals(2, gravadas.size());
        assertEquals(TipoEntrevista.PRIMEIRA_SESSAO, gravadas.get(0).getTipo());
        assertEquals(TipoEntrevista.EXCEPCIONAL, gravadas.get(1).getTipo());
        assertNull(gravadas.get(0).getAvaliacao());
        assertEquals("admin", gravadas.get(0).getEntrevistadorResponsavel().getLogin());

        // O cartão não mudou, e o botão da 1ª sessão sumiu porque ela já foi entrevistada.
        Assistido depois = assistidoRepository.findById(assistido.getId()).orElseThrow();
        assertEquals(CartaoStatus.EM_TRATAMENTO, depois.getStatusCartao());
        assertEquals("P2", depois.getTratamentoAtual().getCodigo());
        mvc.perform(get("/prontuario/" + id).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Entrevista excepcional")))
                .andExpect(content().string(containsString("Acolhimento e explicação do P2.")))
                .andExpect(content().string(not(containsString("Entrevista da 1ª Sessão ("))));

        // O cartão ignora as avulsas ao procurar a entrevista que comunicou as recomendações.
        mvc.perform(get("/prontuario/" + id + "/cartao").with(user(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void excepcionalComCartaoRetidoNaoLiberaENaoSeConfundeComADoTratamento() throws Exception {
        String id = assistido.getId().toString();
        assistido.setStatusCartao(CartaoStatus.AGUARDANDO_AVALIACAO);
        assistidoRepository.save(assistido);

        mvc.perform(post("/prontuario/" + id + "/entrevista-avulsa").param("tipo", "EXCEPCIONAL")
                        .param("data", primeiraSessao.format(BR)).param("observacoes", "Pedido do assistido.")
                        .with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertEquals(CartaoStatus.AGUARDANDO_AVALIACAO,
                assistidoRepository.findById(assistido.getId()).orElseThrow().getStatusCartao());

        // Pela rota avulsa não se registra a entrevista do tratamento: o formulário volta com o erro.
        mvc.perform(post("/prontuario/" + id + "/entrevista-avulsa").param("tipo", "TRATAMENTO")
                        .param("data", primeiraSessao.format(BR)).param("observacoes", "x")
                        .with(user(admin)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tipo de entrevista inválido.")));

        // Sem o que foi conversado, também volta com o erro e nada é gravado além da primeira.
        mvc.perform(post("/prontuario/" + id + "/entrevista-avulsa").param("tipo", "EXCEPCIONAL")
                        .param("data", primeiraSessao.format(BR)).param("observacoes", " ")
                        .with(user(admin)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Registre o que foi conversado")));
        assertEquals(1, entrevistaRepository.findByAssistidoIdOrderByDataDesc(assistido.getId()).size());
    }
}
