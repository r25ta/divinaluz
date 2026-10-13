package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.config.AutoridadesAssistido;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Avaliacao;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
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

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Visibilidade por função de 2026-10-11 (pedido do responsável do projeto, testando a Isabela —
 * Avaliadora e Passista): o Avaliador consulta só os cartões Em Avaliação, o Entrevistador todos
 * menos esses, o Passista só o próprio cartão e o próprio prontuário (os outros dois também
 * abrem o próprio), e quem acumula funções soma as visões. Percorre as telas contra o banco, numa
 * transação desfeita no fim.
 */
@SpringBootTest
@Transactional
class VisibilidadePorFuncaoFluxoTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AssistidoRepository assistidoRepository;
    @Autowired
    private TrabalhadorRepository trabalhadorRepository;
    @Autowired
    private AutoridadesAssistido autoridades;
    @Autowired
    private AvaliacaoRepository avaliacaoRepository;

    private MockMvc mvc;
    private Assistido emAvaliacao;
    private Assistido aguardandoEntrevista;
    private Assistido emTratamento;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        emAvaliacao = assistido("Visib Em Avaliacao", CartaoStatus.AGUARDANDO_AVALIACAO);
        aguardandoEntrevista = assistido("Visib Aguardando Entrevista", CartaoStatus.AGUARDANDO_ENTREVISTA);
        emTratamento = assistido("Visib Em Tratamento", CartaoStatus.EM_TRATAMENTO);
    }

    private Assistido assistido(String nome, CartaoStatus status) {
        Assistido a = new Assistido();
        a.setNome(nome);
        a.setVinculo("ASSISTIDO");
        a.setStatusCartao(status);
        return assistidoRepository.save(a);
    }

    private Assistido trabalhador(String login, CartaoStatus status, TipoTrabalhador... funcoes) {
        Assistido a = assistido("Visib " + login, status);
        a.setVinculo("TRABALHADOR");
        a.setLogin(login);
        a.setSenha("{noop}x");
        a.setPerfilAcesso(PerfilAcesso.TRABALHADOR);
        assistidoRepository.save(a);
        Trabalhador t = new Trabalhador();
        t.setAssistido(a);
        t.setFuncoes(Set.of(funcoes));
        trabalhadorRepository.save(t);
        return a;
    }

    @Test
    void avaliadorEPassistaVeOsEmAvaliacaoEOProprio() throws Exception {
        Assistido isabela = trabalhador("visib.isabela", CartaoStatus.EM_TRATAMENTO,
                TipoTrabalhador.AVALIADOR, TipoTrabalhador.PASSISTA);
        UserDetails eu = autoridades.usuario(isabela);

        // A listagem só com os Em Avaliação, já com o botão da avaliação na linha (a listagem é a fila
        // desde 2026-10-11), e o menu com Prontuário e Meu Prontuário, sem o antigo "Entrevistas".
        mvc.perform(get("/").with(user(eu)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Visib Em Avaliacao")))
                .andExpect(content().string(not(containsString("Visib Em Tratamento"))))
                .andExpect(content().string(not(containsString("Visib Aguardando Entrevista"))))
                .andExpect(content().string(containsString("/prontuario/" + emAvaliacao.getId() + "/nova-avaliacao")))
                .andExpect(content().string(containsString("Meu Prontuário")))
                .andExpect(content().string(not(containsString("href=\"/entrevistas\""))));

        mvc.perform(get("/prontuario/" + emAvaliacao.getId()).with(user(eu))).andExpect(status().isOk());
        mvc.perform(get("/prontuario/" + emAvaliacao.getId() + "/cartao").with(user(eu))).andExpect(status().isOk());
        mvc.perform(get("/prontuario/" + aguardandoEntrevista.getId()).with(user(eu))).andExpect(status().isForbidden());
        mvc.perform(get("/prontuario/" + emTratamento.getId()).with(user(eu))).andExpect(status().isForbidden());
        mvc.perform(get("/prontuario/" + emTratamento.getId() + "/cartao").with(user(eu))).andExpect(status().isForbidden());
        mvc.perform(get("/prontuario/" + isabela.getId()).with(user(eu))).andExpect(status().isOk());

        // O endereço da antiga fila leva à listagem, filtrada no que ela atende.
        mvc.perform(get("/entrevistas").with(user(eu)))
                .andExpect(redirectedUrl("/?status=AGUARDANDO_AVALIACAO"));

        // A escala de preleções é só leitura.
        mvc.perform(get("/prelecao").with(user(eu)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Nova Preleção"))));
        mvc.perform(get("/prelecao/novo").with(user(eu))).andExpect(status().isForbidden());
    }

    @Test
    void entrevistadorVeTodosMenosOsEmAvaliacao() throws Exception {
        Assistido entrevistador = trabalhador("visib.entrevistador", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.ENTREVISTADOR);
        UserDetails eu = autoridades.usuario(entrevistador);

        // A listagem abre filtrada em Aguardando Entrevista, mas tem todos (menos os Em Avaliação).
        mvc.perform(get("/").with(user(eu)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-filtro-inicial=\"AGUARDANDO_ENTREVISTA\"")))
                .andExpect(content().string(containsString("Visib Aguardando Entrevista")))
                .andExpect(content().string(containsString("Visib Em Tratamento")))
                .andExpect(content().string(not(containsString("Visib Em Avaliacao"))))
                .andExpect(content().string(not(containsString("data-filtro=\"AGUARDANDO_AVALIACAO\""))));
        mvc.perform(get("/prontuario/" + aguardandoEntrevista.getId()).with(user(eu))).andExpect(status().isOk());
        mvc.perform(get("/prontuario/" + emTratamento.getId()).with(user(eu))).andExpect(status().isOk());
        mvc.perform(get("/prontuario/" + emTratamento.getId() + "/cartao").with(user(eu))).andExpect(status().isOk());
        mvc.perform(get("/prontuario/" + emAvaliacao.getId()).with(user(eu))).andExpect(status().isForbidden());
        mvc.perform(get("/prontuario/" + emAvaliacao.getId() + "/cartao").with(user(eu))).andExpect(status().isForbidden());
        mvc.perform(get("/prontuario/" + entrevistador.getId()).with(user(eu))).andExpect(status().isOk());

        mvc.perform(get("/entrevistas").with(user(eu)))
                .andExpect(redirectedUrl("/?status=AGUARDANDO_ENTREVISTA"));
    }

    @Test
    void entrevistaPendenteGanhaOBotaoNaLinhaDaListagem() throws Exception {
        Assistido entrevistador = trabalhador("visib.entrev.botao", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.ENTREVISTADOR);
        Avaliacao avaliacao = new Avaliacao();
        avaliacao.setAssistido(aguardandoEntrevista);
        avaliacao.setData(java.time.LocalDate.now());
        avaliacao.setNumeroVez(1);
        avaliacaoRepository.save(avaliacao);

        mvc.perform(get("/").with(user(autoridades.usuario(entrevistador))))
                .andExpect(content().string(containsString("/prontuario/" + aguardandoEntrevista.getId()
                        + "/nova-entrevista?avaliacaoId=" + avaliacao.getId())));
    }

    // O Avaliador continua vendo o que decidiu (2026-10-11): o cartão que ele avaliou, já Aguardando
    // Entrevista, segue na listagem e abre com a decisão à vista; o avaliado por outro, não.
    @Test
    void avaliadorContinuaVendoOQueEleAvaliouAteAEntrevista() throws Exception {
        Assistido avaliador = trabalhador("visib.aval.meu", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.AVALIADOR);
        Assistido outroAvaliador = trabalhador("visib.aval.outro", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.AVALIADOR);
        Assistido avaliadoPorOutro = assistido("Visib Avaliado Por Outro", CartaoStatus.AGUARDANDO_ENTREVISTA);
        avaliacaoComAlta(aguardandoEntrevista, avaliador);
        avaliacaoComAlta(avaliadoPorOutro, outroAvaliador);
        UserDetails eu = autoridades.usuario(avaliador);

        mvc.perform(get("/").with(user(eu)))
                .andExpect(content().string(containsString("Visib Aguardando Entrevista")))
                .andExpect(content().string(not(containsString("Visib Avaliado Por Outro"))))
                .andExpect(content().string(containsString("os que você avaliou")));
        mvc.perform(get("/prontuario/" + aguardandoEntrevista.getId()).with(user(eu)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Reservado até a entrevista"))));
        mvc.perform(get("/prontuario/" + avaliadoPorOutro.getId()).with(user(eu))).andExpect(status().isForbidden());
    }

    private void avaliacaoComAlta(Assistido assistido, Assistido avaliador) {
        Avaliacao avaliacao = new Avaliacao();
        avaliacao.setAssistido(assistido);
        avaliacao.setData(java.time.LocalDate.now());
        avaliacao.setNumeroVez(1);
        avaliacao.setResultado(br.com.nae.divinaluz.model.ResultadoAvaliacao.ALTA);
        avaliacao.setAvaliador(avaliador);
        avaliacaoRepository.save(avaliacao);
    }

    // O nº do prontuário (o quadrado do canto da ficha azul) no cartão e no prontuário.
    @Test
    void cartaoEProntuarioMostramONumeroDoProntuario() throws Exception {
        Assistido avaliador = trabalhador("visib.numero", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.AVALIADOR);
        UserDetails eu = autoridades.usuario(avaliador);
        String numero = String.format("%06d", avaliador.getId());

        mvc.perform(get("/prontuario/" + avaliador.getId() + "/cartao").with(user(eu)))
                .andExpect(content().string(containsString(">" + numero + "<")));
        mvc.perform(get("/prontuario/" + avaliador.getId()).with(user(eu)))
                .andExpect(content().string(containsString(">" + numero + "<")));
    }

    // Listagem de 2026-10-11: o selo TRABALHADOR abre os perfis de trabalho; o Novo Cadastro fica na
    // listagem (saiu do menu). Desde 2026-10-12 o quadro é só informativo, para todos: sem atalho
    // para alterar os perfis, e o selo ASSISTIDO não abre nada, nem para o Dirigente.
    @Test
    void seloDoTrabalhadorMostraOsPerfisENovoCadastroFicaNaListagem() throws Exception {
        Assistido passista = trabalhador("visib.selo.passista", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.PASSISTA);
        Assistido recepcionista = trabalhador("visib.selo.recep", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.RECEPCIONISTA);
        Assistido dirigente = trabalhador("visib.selo.dirig", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.DIRIGENTE);

        for (Assistido quem : List.of(recepcionista, dirigente)) {
            mvc.perform(get("/").with(user(autoridades.usuario(quem))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("popovertarget=\"perfis-" + passista.getId() + "\"")))
                    .andExpect(content().string(containsString(TipoTrabalhador.PASSISTA.getDescricao())))
                    .andExpect(content().string(containsString("dl-lista-novo")))
                    .andExpect(content().string(not(containsString("popovertarget=\"perfis-" + emTratamento.getId() + "\""))))
                    .andExpect(content().string(not(containsString("/trabalhadores/"))))
                    .andExpect(content().string(not(containsString("Alterar perfis"))));
        }
    }

    // "Trabalhadores" saiu do menu (2026-10-11) e virou botão do prontuário, só para quem tem
    // TRABALHADORES; salvar os perfis volta ao prontuário.
    @Test
    void perfisDeTrabalhoFicamNoProntuarioENaoNoMenu() throws Exception {
        Assistido passista = trabalhador("visib.botao.passista", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.PASSISTA);
        Assistido recepcionista = trabalhador("visib.botao.recep", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.RECEPCIONISTA);
        Assistido dirigente = trabalhador("visib.botao.dirig", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.DIRIGENTE);
        UserDetails dirig = autoridades.usuario(dirigente);

        mvc.perform(get("/prontuario/" + passista.getId()).with(user(dirig)))
                .andExpect(content().string(containsString("/trabalhadores/" + passista.getId() + "\"")))
                .andExpect(content().string(containsString("Perfis de Trabalho")))
                .andExpect(content().string(not(containsString("/trabalhadores\""))));
        mvc.perform(get("/prontuario/" + emTratamento.getId()).with(user(dirig)))
                .andExpect(content().string(containsString("Tornar Trabalhador")));
        // Quem não tem TRABALHADORES (a Recepcionista, por exemplo) não vê o botão.
        mvc.perform(get("/prontuario/" + passista.getId()).with(user(autoridades.usuario(recepcionista))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/trabalhadores/" + passista.getId()))));

        mvc.perform(post("/trabalhadores/" + passista.getId()).with(user(dirig)).with(csrf())
                        .param("funcoes", "PASSISTA"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/prontuario/" + passista.getId()));

        // A lista /trabalhadores (e o redirecionamento da antiga /usuarios) saiu em 2026-10-12.
        mvc.perform(get("/trabalhadores").with(user(dirig))).andExpect(status().isNotFound());
        mvc.perform(get("/usuarios").with(user(dirig))).andExpect(status().isNotFound());
    }

    // Health check público para monitor externo (2026-10-11), só com o status.
    @Test
    void healthCheckEPublicoESoComOStatus() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\"")))
                .andExpect(content().string(not(containsString("components"))));
    }

    @Test
    void entrevistadorNaoAbreOProprioProntuarioAguardandoEntrevista() throws Exception {
        Assistido entrevistador = trabalhador("visib.entrev.retido", CartaoStatus.AGUARDANDO_ENTREVISTA,
                TipoTrabalhador.ENTREVISTADOR);
        UserDetails eu = autoridades.usuario(entrevistador);

        mvc.perform(get("/prontuario/" + entrevistador.getId()).with(user(eu)))
                .andExpect(redirectedUrl("/prontuario/" + entrevistador.getId() + "/cartao"));
        // Na listagem ele aparece, mas sem ação sobre si mesmo.
        mvc.perform(get("/").with(user(eu)))
                .andExpect(content().string(containsString("É você")));
    }

    @Test
    void avaliadorAbreOProprioProntuario() throws Exception {
        Assistido avaliador = trabalhador("visib.avaliador", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.AVALIADOR);
        mvc.perform(get("/prontuario/" + avaliador.getId()).with(user(autoridades.usuario(avaliador))))
                .andExpect(status().isOk());
    }

    // 2026-10-12: Passista e Preletor não veem prontuário nenhum, nem o próprio — só o próprio cartão.
    @Test
    void passistaEPreletorSoOProprioCartao() throws Exception {
        Assistido passista = trabalhador("visib.passista", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.PASSISTA);
        Assistido preletor = trabalhador("visib.preletor", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.EXPOSITOR_PRELETOR);

        for (Assistido quem : List.of(passista, preletor)) {
            UserDetails eu = autoridades.usuario(quem);
            mvc.perform(get("/").with(user(eu)))
                    .andExpect(redirectedUrl("/prontuario/" + quem.getId() + "/cartao"));
            mvc.perform(get("/prontuario/" + quem.getId()).with(user(eu))).andExpect(status().isForbidden());
            mvc.perform(get("/prontuario/" + quem.getId() + "/cartao").with(user(eu)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("Meu Prontuário"))));
            for (Assistido outro : new Assistido[] {emAvaliacao, aguardandoEntrevista, emTratamento}) {
                mvc.perform(get("/prontuario/" + outro.getId()).with(user(eu))).andExpect(status().isForbidden());
                mvc.perform(get("/prontuario/" + outro.getId() + "/cartao").with(user(eu))).andExpect(status().isForbidden());
            }
        }
    }

    // O Administrador vê e faz tudo, sem precisar de função (2026-10-12).
    @Test
    void administradorAlcancaTodosOsModulos() throws Exception {
        Assistido admin = assistido("Visib Administrador", CartaoStatus.EM_TRATAMENTO);
        admin.setLogin("visib.admin");
        admin.setSenha("{noop}x");
        admin.setPerfilAcesso(PerfilAcesso.ADMINISTRADOR);
        assistidoRepository.save(admin);
        UserDetails eu = autoridades.usuario(admin);

        for (String url : List.of("/", "/prontuario/" + emAvaliacao.getId(), "/prontuario/" + aguardandoEntrevista.getId(),
                "/prontuario/" + emTratamento.getId(), "/prontuario/" + emTratamento.getId() + "/editar",
                "/trabalhadores/" + emTratamento.getId(), "/prontuario/" + emAvaliacao.getId() + "/nova-avaliacao",
                "/sessao", "/prelecao/novo", "/novo", "/auditoria")) {
            mvc.perform(get(url).with(user(eu))).andExpect(status().isOk());
        }
    }

    // O Dirigente acumula todos os perfis (2026-10-12): marcado com outros, fica gravado só ele — e
    // continua podendo ser o preletor da escala.
    @Test
    void dirigenteFicaSoComoDirigenteEPodeSerPreletor() throws Exception {
        Assistido dirigente = trabalhador("visib.dirig.acumula", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.DIRIGENTE);
        UserDetails eu = autoridades.usuario(dirigente);

        mvc.perform(post("/trabalhadores/" + emTratamento.getId()).with(user(eu)).with(csrf())
                        .param("funcoes", "DIRIGENTE", "PASSISTA", "EXPOSITOR_PRELETOR"))
                .andExpect(status().is3xxRedirection());
        assertEquals(Set.of(TipoTrabalhador.DIRIGENTE),
                trabalhadorRepository.findByAssistidoId(emTratamento.getId()).orElseThrow().getFuncoes());

        mvc.perform(get("/prelecao/novo").with(user(eu)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Visib Em Tratamento")));
        mvc.perform(get("/trabalhadores/" + emTratamento.getId()).with(user(eu)))
                .andExpect(content().string(containsString("acumula todos os perfis")));
    }

    // ...a não ser que acumule outra função: a visão é sempre a da soma das funções.
    @Test
    void passistaQueTambemEntrevistaGanhaAVisaoDoEntrevistador() throws Exception {
        Assistido duplo = trabalhador("visib.passista.entrev", CartaoStatus.EM_TRATAMENTO,
                TipoTrabalhador.PASSISTA, TipoTrabalhador.ENTREVISTADOR);
        UserDetails eu = autoridades.usuario(duplo);

        mvc.perform(get("/prontuario/" + duplo.getId()).with(user(eu))).andExpect(status().isOk());
        mvc.perform(get("/prontuario/" + emTratamento.getId()).with(user(eu))).andExpect(status().isOk());
    }

    // 2026-10-12: a Recepcionista edita dados e acesso, vincula celular e desativa, mas não registra
    // avaliação nem entrevista e não promove nem mexe nos perfis de trabalho (só o Dirigente).
    @Test
    void recepcionistaEditaOCadastroMasNaoAvaliaEntrevistaNemDefinePerfis() throws Exception {
        Assistido recepcionista = trabalhador("visib.recep.perfis", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.RECEPCIONISTA);
        Assistido passista = trabalhador("visib.recep.alvo", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.PASSISTA);
        UserDetails eu = autoridades.usuario(recepcionista);

        mvc.perform(get("/prontuario/" + emTratamento.getId()).with(user(eu)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Editar Dados")))
                .andExpect(content().string(not(containsString("/trabalhadores/" + emTratamento.getId()))));
        mvc.perform(get("/prontuario/" + emTratamento.getId() + "/editar").with(user(eu))).andExpect(status().isOk());
        mvc.perform(get("/trabalhadores/" + passista.getId()).with(user(eu))).andExpect(status().isForbidden());
        mvc.perform(post("/trabalhadores/" + emTratamento.getId()).with(user(eu)).with(csrf())
                        .param("funcoes", "DIRIGENTE"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/prontuario/" + emAvaliacao.getId() + "/nova-avaliacao").with(user(eu)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/prontuario/" + aguardandoEntrevista.getId() + "/nova-entrevista").with(user(eu)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/prontuario/" + emTratamento.getId() + "/nova-entrevista-avulsa")
                        .param("tipo", "EXCEPCIONAL").with(user(eu)))
                .andExpect(status().isForbidden());
    }
}
