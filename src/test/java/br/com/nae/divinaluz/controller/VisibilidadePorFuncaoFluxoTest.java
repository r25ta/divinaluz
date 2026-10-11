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

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void passistaSoOProprioCartaoEOProprioProntuario() throws Exception {
        Assistido passista = trabalhador("visib.passista", CartaoStatus.EM_TRATAMENTO, TipoTrabalhador.PASSISTA);
        UserDetails eu = autoridades.usuario(passista);

        mvc.perform(get("/").with(user(eu)))
                .andExpect(redirectedUrl("/prontuario/" + passista.getId() + "/cartao"));
        mvc.perform(get("/prontuario/" + passista.getId()).with(user(eu))).andExpect(status().isOk());
        mvc.perform(get("/prontuario/" + passista.getId() + "/cartao").with(user(eu))).andExpect(status().isOk());
        for (Assistido outro : new Assistido[] {emAvaliacao, aguardandoEntrevista, emTratamento}) {
            mvc.perform(get("/prontuario/" + outro.getId()).with(user(eu))).andExpect(status().isForbidden());
            mvc.perform(get("/prontuario/" + outro.getId() + "/cartao").with(user(eu))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/entrevistas").with(user(eu))).andExpect(status().isForbidden());
    }

    @Test
    void passistaComOProprioCartaoRetidoVaiAoCartao() throws Exception {
        Assistido passista = trabalhador("visib.retido", CartaoStatus.AGUARDANDO_ENTREVISTA, TipoTrabalhador.PASSISTA);

        mvc.perform(get("/prontuario/" + passista.getId()).with(user(autoridades.usuario(passista))))
                .andExpect(redirectedUrl("/prontuario/" + passista.getId() + "/cartao"));
    }
}
