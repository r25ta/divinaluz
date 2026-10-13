package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.config.AutoridadesAssistido;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Módulo de Auditoria (2026-10-12): uma alteração feita pela tela vira registro com quem fez, de
 * onde, o valor anterior e o novo; senhas não aparecem; as funções do trabalhador (coleção) também
 * são registradas; e só o Administrador consulta. Numa transação desfeita no fim.
 */
@SpringBootTest
@Transactional
class AuditoriaFluxoTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private AssistidoRepository assistidoRepository;
    @Autowired
    private TrabalhadorRepository trabalhadorRepository;
    @Autowired
    private AutoridadesAssistido autoridades;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private UserDetails admin;
    private Assistido assistido;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = userDetailsService.loadUserByUsername("admin");
        assistido = new Assistido();
        assistido.setNome("Auditoria Nome Antigo");
        assistido.setVinculo("ASSISTIDO");
        assistido.setSenha("hash-que-nao-pode-aparecer");
        assistidoRepository.saveAndFlush(assistido);
    }

    private List<Map<String, Object>> campos(String acao) {
        return jdbc.queryForList("""
                SELECT a.usuario_login, a.origem, c.campo, c.valor_anterior, c.valor_novo
                  FROM auditoria a JOIN auditoria_campo c ON c.auditoria_id = a.id
                 WHERE a.entidade = 'Assistido' AND a.registro_id = ? AND a.acao = ?
                """, assistido.getId().toString(), acao);
    }

    // Sem a transação do teste: a alteração precisa ser gravada DENTRO da requisição, como na vida
    // real (com a transação do teste, ela só iria ao banco no flush, já sem o usuário da requisição).
    // Por isso apaga à mão o que gravou.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void alteracaoPelaTelaGuardaQuemOndeEOsDoisValores() throws Exception {
        try {
            conferirAlteracaoPelaTela();
        } finally {
            jdbc.update("DELETE FROM auditoria WHERE assistido_id = ?", assistido.getId());
            jdbc.update("DELETE FROM assistido WHERE id = ?", assistido.getId());
        }
    }

    private void conferirAlteracaoPelaTela() throws Exception {
        // A inclusão (feita sem login) não tem usuário, e a senha não aparece. No teste ela sai
        // "(sem login)" e não "(sistema)" porque o Spring de teste simula uma requisição na thread.
        List<Map<String, Object>> inclusao = campos("INCLUSAO");
        assertTrue(inclusao.stream().anyMatch(c -> "nome".equals(c.get("campo"))
                && "Auditoria Nome Antigo".equals(c.get("valor_novo"))));
        assertTrue(inclusao.stream().filter(c -> "senha".equals(c.get("campo")))
                .allMatch(c -> "(oculto)".equals(c.get("valor_novo"))));
        assertTrue(List.of("(sistema)", "(sem login)").contains(inclusao.get(0).get("usuario_login")));

        mvc.perform(post("/prontuario/" + assistido.getId() + "/editar").with(user(admin)).with(csrf())
                        .param("nome", "Auditoria Nome Novo").param("bairro", "Centro"))
                .andExpect(status().is3xxRedirection());

        List<Map<String, Object>> alteracao = campos("ALTERACAO");
        Map<String, Object> nome = alteracao.stream().filter(c -> "nome".equals(c.get("campo"))).findFirst().orElseThrow();
        assertEquals("Auditoria Nome Antigo", nome.get("valor_anterior"));
        assertEquals("Auditoria Nome Novo", nome.get("valor_novo"));
        assertEquals("admin", nome.get("usuario_login"));
        assertEquals("POST /prontuario/" + assistido.getId() + "/editar", nome.get("origem"));

        mvc.perform(get("/auditoria").param("assistidoId", assistido.getId().toString()).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Auditoria Nome Antigo")))
                .andExpect(content().string(containsString("Auditoria Nome Novo")))
                .andExpect(content().string(not(containsString("hash-que-nao-pode-aparecer"))));
    }

    @Test
    void funcoesDoTrabalhadorSaoRegistradas() throws Exception {
        mvc.perform(post("/trabalhadores/" + assistido.getId()).with(user(admin)).with(csrf())
                        .param("funcoes", "PASSISTA"))
                .andExpect(status().is3xxRedirection());
        entityManager.flush();

        List<String> funcoes = jdbc.queryForList("""
                SELECT c.valor_novo FROM auditoria a JOIN auditoria_campo c ON c.auditoria_id = a.id
                 WHERE a.entidade = 'Trabalhador' AND a.assistido_id = ? AND c.campo = 'funcoes'
                """, String.class, assistido.getId());
        assertEquals(List.of("Passista"), funcoes);
    }

    @Test
    void soOAdministradorConsulta() throws Exception {
        Assistido dirigente = new Assistido();
        dirigente.setNome("Auditoria Dirigente");
        dirigente.setVinculo("TRABALHADOR");
        dirigente.setLogin("auditoria.dirigente");
        dirigente.setSenha("{noop}x");
        dirigente.setPerfilAcesso(PerfilAcesso.TRABALHADOR);
        assistidoRepository.save(dirigente);
        Trabalhador t = new Trabalhador();
        t.setAssistido(dirigente);
        t.setFuncoes(Set.of(TipoTrabalhador.DIRIGENTE));
        trabalhadorRepository.save(t);

        mvc.perform(get("/auditoria").with(user(autoridades.usuario(dirigente)))).andExpect(status().isForbidden());
        mvc.perform(get("/prontuario/" + assistido.getId()).with(user(autoridades.usuario(dirigente))))
                .andExpect(content().string(not(containsString("href=\"/auditoria\""))));
        mvc.perform(get("/prontuario/" + assistido.getId()).with(user(admin)))
                .andExpect(content().string(containsString("href=\"/auditoria\"")));
    }
}
