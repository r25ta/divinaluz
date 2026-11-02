package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.config.AutoridadesAssistido;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.SessaoAssistenciaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pedidos do responsável do projeto de 2026-11-02: (1) o cartão/cadastro nasce no dia de uma sessão
 * válida, e só o Administrador cadastra sem amarrar a data do tratamento; (2) a listagem de Prontuário
 * mostra a última presença e, num quadro, as presenças do tratamento em andamento. Contra o banco, numa
 * transação desfeita no fim. Como "hoje" muda a cada execução, o teste da recepção confere o caminho que
 * vale para o dia em que roda.
 */
@SpringBootTest
@Transactional
class CadastroDiaDeSessaoFluxoTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AssistidoRepository assistidoRepository;
    @Autowired
    private TrabalhadorRepository trabalhadorRepository;
    @Autowired
    private SessaoRepository sessaoRepository;
    @Autowired
    private SessaoAssistenciaRepository sessaoAssistenciaRepository;
    @Autowired
    private AutoridadesAssistido autoridades;
    @Autowired
    private ProntuarioController controller;

    private MockMvc mvc;
    private UserDetails admin;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = autoridades.usuario(assistidoRepository.findByLogin("admin").orElseThrow());
    }

    private UserDetails recepcionista() {
        Assistido a = new Assistido();
        a.setNome("Cadastro Dia Recepcionista");
        a.setVinculo("TRABALHADOR");
        a.setLogin("cadastro.dia.recep");
        a.setSenha("{noop}x");
        a.setPerfilAcesso(PerfilAcesso.TRABALHADOR);
        assistidoRepository.save(a);
        Trabalhador t = new Trabalhador();
        t.setAssistido(a);
        t.setFuncoes(Set.of(TipoTrabalhador.RECEPCIONISTA));
        trabalhadorRepository.save(t);
        return autoridades.usuario(a);
    }

    private static LocalDate domingoLonge() {
        return LocalDate.of(2099, 1, 1).with(TemporalAdjusters.next(DayOfWeek.SUNDAY));
    }

    private SessaoAssistencia sessao(LocalDate data) {
        SessaoAssistencia s = new SessaoAssistencia();
        s.setData(data);
        s.setDiaFrequencia(DiaFrequencia.DOMINGO_08H);
        return s;
    }

    @Test
    void soEDiaDeCadastroNumDomingoOuTercaComASessaoValida() {
        LocalDate domingo = domingoLonge();
        assertNotNull(controller.motivoCadastroBloqueado(domingo.plusDays(1)), "segunda-feira não é dia de sessão");
        assertNull(controller.motivoCadastroBloqueado(domingo), "domingo sem sessão aberta ainda vale");
        assertNull(controller.motivoCadastroBloqueado(domingo.plusDays(2)), "terça vale");

        SessaoAssistencia cancelada = sessao(domingo);
        cancelada.setCanceladaEm(LocalDateTime.now());
        cancelada.setMotivoCancelamento("Chuva");
        sessaoAssistenciaRepository.save(cancelada);
        assertTrue(controller.motivoCadastroBloqueado(domingo).contains("cancelada"));

        LocalDate outroDomingo = domingo.plusWeeks(1);
        SessaoAssistencia encerrada = sessao(outroDomingo);
        encerrada.setEncerradaEm(LocalDateTime.now());
        sessaoAssistenciaRepository.save(encerrada);
        assertTrue(controller.motivoCadastroBloqueado(outroDomingo).contains("encerrada"));
    }

    @Test
    void recepcaoSoCadastraHojeEADataEnviadaNaoValeNada() throws Exception {
        UserDetails recep = recepcionista();
        String nome = "Cadastro Dia Recepcao Novo";
        LocalDate hoje = LocalDate.now();
        boolean diaDeCadastro = controller.motivoCadastroBloqueado(hoje) == null;

        // A data enviada é um domingo antigo: fora do Administrador ela é ignorada.
        mvc.perform(post("/salvar").with(user(recep)).with(csrf())
                        .param("nome", nome)
                        .param("login", "cadastro.dia.novo")
                        .param("senhaAcesso", "senha123").param("confirmacaoSenhaAcesso", "senha123")
                        .param("dataPrimeiraSessao", "07/01/2024"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        var gravado = assistidoRepository.findAll().stream().filter(a -> nome.equals(a.getNome())).findFirst();
        if (diaDeCadastro) {
            assertTrue(gravado.isPresent());
            assertEquals(hoje, gravado.get().getCicloIniciadoEm(), "a 1ª presença é a sessão de hoje");
            assertEquals("P2", gravado.get().getTratamentoAtual().getCodigo());
        } else {
            assertTrue(gravado.isEmpty(), "fora do dia da sessão nada é gravado");
            mvc.perform(get("/novo").with(user(recep)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"));
            mvc.perform(get("/").with(user(recep)))
                    .andExpect(content().string(containsString("Só em dia de sessão")));
        }
    }

    @Test
    void administradorCadastraSemDataESemTratamento() throws Exception {
        String nome = "Cadastro Dia Admin Sem Data";
        mvc.perform(get("/novo").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("(opcional)")));

        mvc.perform(post("/salvar").with(user(admin)).with(csrf())
                        .param("nome", nome)
                        .param("login", "cadastro.dia.admin")
                        .param("senhaAcesso", "senha123").param("confirmacaoSenhaAcesso", "senha123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        Assistido gravado = assistidoRepository.findAll().stream()
                .filter(a -> nome.equals(a.getNome())).findFirst().orElseThrow();
        assertNull(gravado.getTratamentoAtual());
        assertNull(gravado.getCicloIniciadoEm());
        assertNull(gravado.getDiaFrequencia());
        assertEquals(0, sessaoRepository.countByAssistidoId(gravado.getId()));
    }

    @Test
    void listagemMostraAUltimaPresencaEAsDoCicloAtual() throws Exception {
        Assistido a = new Assistido();
        a.setNome("Cadastro Dia Com Presencas");
        a.setVinculo("ASSISTIDO");
        a.setStatusCartao(CartaoStatus.EM_TRATAMENTO);
        a.setCicloIniciadoEm(LocalDate.of(2025, 3, 2));
        assistidoRepository.save(a);
        presenca(a, LocalDate.of(2025, 2, 9), 4, false);   // ciclo anterior: não entra no quadro
        presenca(a, LocalDate.of(2025, 3, 2), 1, false);
        presenca(a, LocalDate.of(2025, 3, 4), null, true);
        presenca(a, LocalDate.of(2025, 3, 9), 2, false);

        String pagina = mvc.perform(get("/").with(user(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(pagina.contains("Última Presença"));
        assertTrue(pagina.contains("data-sort-chave=\"2025-03-09\""), "a última presença, ordenável pela data");
        int quadro = pagina.indexOf("id=\"presencas-" + a.getId() + "\"");
        assertTrue(quadro > 0, "o quadro das presenças da pessoa");
        String doQuadro = pagina.substring(quadro, pagina.indexOf("</div>", pagina.indexOf("</ul>", quadro)));
        assertTrue(doQuadro.contains("09/03/2025") && doQuadro.contains("2ª sessão"));
        assertTrue(doQuadro.contains("04/03/2025") && doQuadro.contains("Ouvinte"));
        assertTrue(doQuadro.contains("02/03/2025") && doQuadro.contains("1ª sessão"));
        assertTrue(!doQuadro.contains("09/02/2025"), "presença de um ciclo encerrado fica fora");
    }

    private void presenca(Assistido a, LocalDate data, Integer numero, boolean ouvinte) {
        SessaoTratamento s = new SessaoTratamento();
        s.setAssistido(a);
        s.setDataConsulta(data);
        s.setNumeroSerie(numero);
        s.setOuvinte(ouvinte);
        sessaoRepository.save(s);
    }
}
