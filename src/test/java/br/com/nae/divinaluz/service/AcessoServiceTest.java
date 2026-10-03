package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Regras do acesso no cadastro (2026-10-03): e-mail único, login único e sugestão pelo nome. */
@ExtendWith(MockitoExtension.class)
class AcessoServiceTest {

    @Mock
    private AssistidoRepository assistidoRepository;

    @Mock
    private EmailService emailService;

    private AcessoService acessoService;

    @BeforeEach
    void preparar() {
        acessoService = new AcessoService(assistidoRepository, new BCryptPasswordEncoder(), emailService);
    }

    private static Assistido pessoa(long id, String nome) {
        Assistido a = new Assistido();
        a.setId(id);
        a.setNome(nome);
        a.setAtivo(true);
        return a;
    }

    // ------------------------------------------------------------ sugestão de login

    @Test
    void sugestaoUsaPrimeiroEUltimoNomeSemAcentoNemParticulas() {
        assertEquals("joao.souza", AcessoService.baseDoLogin("João da Silva Souza"));
        assertEquals("ana.avila", AcessoService.baseDoLogin("Ana D'Ávila"));
        assertEquals("maria", AcessoService.baseDoLogin("  Maria  "));
        assertEquals("assistido.li", AcessoService.baseDoLogin("Li"));
        assertEquals("assistido", AcessoService.baseDoLogin("   "));
    }

    @Test
    void sugestaoGanhaNumeroQuandoOLoginJaExiste() {
        when(assistidoRepository.findFirstByLoginIgnoreCase("joao.souza")).thenReturn(Optional.of(pessoa(1, "Outro João")));
        when(assistidoRepository.findFirstByLoginIgnoreCase("joao.souza2")).thenReturn(Optional.of(pessoa(2, "Mais um")));

        assertEquals("joao.souza3", acessoService.sugerirLogin("João Souza", null));
    }

    @Test
    void oProprioLoginNaoContaComoOcupadoNaEdicao() {
        when(assistidoRepository.findFirstByLoginIgnoreCase("joao.souza")).thenReturn(Optional.of(pessoa(5, "João Souza")));

        assertEquals("joao.souza", acessoService.sugerirLogin("João Souza", 5L));
    }

    // ------------------------------------------------------------ login

    @Test
    void loginRecusaFormatoInvalido() {
        assertTrue(acessoService.problemaComLogin("ab", null).isPresent(), "curto demais");
        assertTrue(acessoService.problemaComLogin("joão.silva", null).isPresent(), "acento");
        assertTrue(acessoService.problemaComLogin("joao silva", null).isPresent(), "espaço");
        assertTrue(acessoService.problemaComLogin(".joao", null).isPresent(), "começa com ponto");
        assertTrue(acessoService.problemaComLogin("  ", null).isPresent(), "em branco");
    }

    @Test
    void loginEmUsoSemDiferenciarMaiusculasERecusado() {
        when(assistidoRepository.findFirstByLoginIgnoreCase("maria.souza")).thenReturn(Optional.of(pessoa(1, "Maria")));

        Optional<String> problema = acessoService.problemaComLogin("Maria.Souza", 2L);

        assertTrue(problema.isPresent());
        assertTrue(problema.get().contains("já está em uso"));
    }

    @Test
    void loginLivreENormalizadoPassa() {
        assertFalse(acessoService.problemaComLogin(" Maria.Souza ", null).isPresent());
        assertEquals("maria.souza", AcessoService.normalizarLogin(" Maria.Souza "));
    }

    // ------------------------------------------------------------ e-mail

    @Test
    void emailDeOutroCadastroERecusadoDizendoDeQuemE() {
        Assistido dono = pessoa(1, "Maria das Dores");
        dono.setAtivo(false);
        when(assistidoRepository.findFirstByEmailIgnoreCase("maria@exemplo.com")).thenReturn(Optional.of(dono));

        RegraNegocioException erro = assertThrows(RegraNegocioException.class,
                () -> acessoService.validarEmail(" Maria@Exemplo.com ", 2L));

        assertTrue(erro.getMessage().contains("Maria das Dores"));
        assertTrue(erro.getMessage().contains("desativado"), "avisa que o cadastro existente está desativado");
    }

    @Test
    void oProprioEmailEEmailEmBrancoPassam() {
        when(assistidoRepository.findFirstByEmailIgnoreCase("maria@exemplo.com")).thenReturn(Optional.of(pessoa(1, "Maria")));

        assertDoesNotThrow(() -> acessoService.validarEmail("maria@exemplo.com", 1L));
        assertDoesNotThrow(() -> acessoService.validarEmail("   ", null));
        assertNull(AcessoService.normalizarEmail("   "));
    }

    // ------------------------------------------------------------ senha

    @Test
    void senhaSoPodeFaltarComEmailEEnvioLigado() {
        when(emailService.isHabilitado()).thenReturn(false);
        assertTrue(acessoService.senhaObrigatoria(null), "sem e-mail");
        assertTrue(acessoService.senhaObrigatoria("maria@exemplo.com"), "e-mail com envio desligado");

        when(emailService.isHabilitado()).thenReturn(true);
        assertFalse(acessoService.senhaObrigatoria("maria@exemplo.com"), "e-mail com envio ligado");
    }

    @Test
    void senhaCurtaOuSemConfirmacaoERecusada() {
        assertThrows(RegraNegocioException.class, () -> acessoService.validarSenha("123", "123", true, "x"));
        assertThrows(RegraNegocioException.class, () -> acessoService.validarSenha("senha123", "outra", true, "x"));
        assertThrows(RegraNegocioException.class, () -> acessoService.validarSenha("", "", true, "obrigatória"));
        assertDoesNotThrow(() -> acessoService.validarSenha("", "", false, "x"));
        assertDoesNotThrow(() -> acessoService.validarSenha("senha123", "senha123", true, "x"));
    }

    // ------------------------------------------------------------ criação

    @Test
    void criarAcessoDeTrabalhadorJaNasceComPerfilDeTrabalhador() {
        Assistido olivia = pessoa(9, "Olivia Cardoso");
        olivia.setVinculo(TrabalhadorService.VINCULO_TRABALHADOR);

        acessoService.criarAcesso(olivia, " Olivia.Cardoso ", "senha123");

        assertEquals("olivia.cardoso", olivia.getLogin());
        assertEquals(PerfilAcesso.TRABALHADOR, olivia.getPerfilAcesso());
        assertTrue(olivia.isAcessoAtivo());
        assertTrue(new BCryptPasswordEncoder().matches("senha123", olivia.getSenha()));
        verify(assistidoRepository).save(olivia);
    }

    @Test
    void semSenhaNaoGravaSenhaENaoAvisaComEnvioDesligado() {
        Assistido maria = pessoa(3, "Maria");
        maria.setVinculo(TrabalhadorService.VINCULO_ASSISTIDO);
        maria.setEmail("maria@exemplo.com");

        acessoService.criarAcesso(maria, "maria", "");

        assertNull(maria.getSenha());
        assertEquals(PerfilAcesso.ASSISTIDO, maria.getPerfilAcesso());
        verify(emailService, never()).enviarBoasVindas(anyString(), anyString(), anyString());
    }
}
