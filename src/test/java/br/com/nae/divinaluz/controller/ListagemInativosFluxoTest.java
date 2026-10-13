package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.config.AutoridadesAssistido;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Mostrar inativos" da listagem de Prontuário (2026-10-13, o responsável do projeto achou que o link não
 * funcionava): o link diz quantos são, os inativos vêm no topo marcados como inativos, e sem o filtro eles
 * não aparecem. Contra o banco, como o administrador das migrations, numa transação desfeita no fim.
 */
@SpringBootTest
@Transactional
class ListagemInativosFluxoTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AssistidoRepository assistidoRepository;
    @Autowired
    private AutoridadesAssistido autoridades;

    private MockMvc mvc;
    private UserDetails admin;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = autoridades.usuario(assistidoRepository.findByLogin("admin").orElseThrow());
        cadastro("Listagem Ativo Aaa", true);
        cadastro("Listagem Inativo Zzz", false);
    }

    private void cadastro(String nome, boolean ativo) {
        Assistido a = new Assistido();
        a.setNome(nome);
        a.setVinculo("ASSISTIDO");
        a.setAtivo(ativo);
        assistidoRepository.save(a);
    }

    @Test
    void semOFiltroOInativoNaoApareceEOLinkDizQuantosSao() throws Exception {
        mvc.perform(get("/").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Listagem Ativo Aaa")))
                .andExpect(content().string(not(containsString("Listagem Inativo Zzz"))))
                .andExpect(content().string(containsString("Mostrar inativos (")))
                .andExpect(content().string(containsString("mostrarInativos=true")));
    }

    @Test
    void comOFiltroOInativoApareceNoTopoMarcado() throws Exception {
        String pagina = mvc.perform(get("/").param("mostrarInativos", "true").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mostrar somente ativos")))
                .andExpect(content().string(containsString("dl-situacao-inativo")))
                .andReturn().getResponse().getContentAsString();

        int inativo = pagina.indexOf("Listagem Inativo Zzz");
        int ativo = pagina.indexOf("Listagem Ativo Aaa");
        assertTrue(inativo > 0 && ativo > 0, "os dois cadastros aparecem");
        assertTrue(inativo < ativo, "o inativo vem antes, mesmo sendo 'Z' — senão some no meio da lista");
    }
}
