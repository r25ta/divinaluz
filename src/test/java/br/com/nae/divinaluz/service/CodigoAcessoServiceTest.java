package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodigoAcessoServiceTest {

    @Mock
    private AssistidoRepository assistidoRepository;

    @Mock
    private EmailService emailService;

    // Encoder de verdade (e não mock): metade do que se quer provar aqui é que o que vai para o banco
    // é o hash e não o código, e isso só se observa com um encoder real.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private CodigoAcessoService codigoAcessoService;

    private static final String EMAIL = "maria@exemplo.com";

    private Assistido maria;

    @BeforeEach
    void preparar() {
        codigoAcessoService = new CodigoAcessoService(assistidoRepository, passwordEncoder, emailService);
        maria = new Assistido();
        maria.setId(7L);
        maria.setNome("Maria das Dores");
        maria.setLogin(EMAIL);
        maria.setAcessoAtivo(true);
    }

    /** Envia o código e devolve o que foi mandado no e-mail. */
    private String solicitarECapturarCodigo() {
        when(assistidoRepository.findByLoginIgnoreCaseAndAcessoAtivoTrue(EMAIL)).thenReturn(Optional.of(maria));
        codigoAcessoService.solicitar(EMAIL);
        ArgumentCaptor<String> codigo = ArgumentCaptor.forClass(String.class);
        verify(emailService).enviarCodigoAcesso(eq(EMAIL), anyString(), codigo.capture(), anyInt());
        return codigo.getValue();
    }

    @Test
    void solicitarEnviaCodigoDeSeisDigitosEGuardaApenasOHash() {
        String codigo = solicitarECapturarCodigo();

        assertTrue(codigo.matches("\\d{6}"), "o código deve ter exatamente 6 dígitos: " + codigo);
        assertNotEquals(codigo, maria.getCodigoAcesso(), "o código não pode ser gravado em texto puro");
        assertTrue(passwordEncoder.matches(codigo, maria.getCodigoAcesso()),
                "o que foi gravado deve ser o hash do código enviado");
        assertEquals(0, maria.getCodigoAcessoTentativas());
        assertTrue(maria.getCodigoAcessoExpiraEm().isAfter(LocalDateTime.now()));
        verify(assistidoRepository).save(maria);
    }

    @Test
    void desdeAV35APessoaEAchadaPeloEmailDoCadastroEOCodigoVaiParaEle() {
        // Login sugerido pelo nome, e-mail no campo próprio: é o e-mail que a pessoa digita em /entrar.
        maria.setLogin("maria.dores");
        maria.setEmail(EMAIL);
        when(assistidoRepository.findFirstByEmailIgnoreCaseAndLoginIsNotNullAndAcessoAtivoTrue("Maria@Exemplo.com"))
                .thenReturn(Optional.of(maria));

        codigoAcessoService.solicitar("Maria@Exemplo.com");

        verify(emailService).enviarCodigoAcesso(eq(EMAIL), anyString(), anyString(), anyInt());
        verify(assistidoRepository, never()).findByLoginIgnoreCaseAndAcessoAtivoTrue(anyString());
    }

    @Test
    void solicitarParaEmailDesconhecidoNaoEnviaNadaENaoFalha() {
        when(assistidoRepository.findByLoginIgnoreCaseAndAcessoAtivoTrue("ninguem@exemplo.com"))
                .thenReturn(Optional.empty());

        codigoAcessoService.solicitar("ninguem@exemplo.com");

        verify(emailService, never()).enviarCodigoAcesso(anyString(), anyString(), anyString(), anyInt());
        verify(assistidoRepository, never()).save(any());
    }

    @Test
    void solicitarDentroDoIntervaloMinimoNaoReenvia() {
        String primeiro = solicitarECapturarCodigo();
        assertTrue(primeiro.matches("\\d{6}"));

        // Segundo pedido imediato: o intervalo mínimo deve barrar, para o formulário não virar bomba
        // de e-mail na caixa de uma pessoa.
        codigoAcessoService.solicitar(EMAIL);

        verify(emailService).enviarCodigoAcesso(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void conferirCodigoCorretoAutorizaELimpaOCodigo() {
        String codigo = solicitarECapturarCodigo();

        CodigoAcessoService.Conferencia conferencia = codigoAcessoService.conferir(EMAIL, codigo);

        assertTrue(conferencia.ok());
        assertEquals(maria, conferencia.assistido());
        assertNull(maria.getCodigoAcesso(), "código é de uso único: deve sumir depois de usado");
        assertNull(maria.getCodigoAcessoExpiraEm());
    }

    @Test
    void conferirIgnoraEspacosDigitadosNoCodigo() {
        String codigo = solicitarECapturarCodigo();

        assertTrue(codigoAcessoService.conferir(EMAIL, " " + codigo + " ").ok());
    }

    @Test
    void conferirCodigoErradoContaTentativaENaoAutoriza() {
        String codigo = solicitarECapturarCodigo();
        String errado = codigo.equals("000000") ? "999999" : "000000";

        CodigoAcessoService.Conferencia conferencia = codigoAcessoService.conferir(EMAIL, errado);

        assertFalse(conferencia.ok());
        assertEquals(CodigoAcessoService.Resultado.INVALIDO, conferencia.resultado());
        assertEquals(1, maria.getCodigoAcessoTentativas());
        assertNull(conferencia.assistido());
    }

    @Test
    void conferirDescartaOCodigoNoLimiteDeTentativas() {
        String codigo = solicitarECapturarCodigo();
        String errado = codigo.equals("000000") ? "999999" : "000000";

        CodigoAcessoService.Conferencia ultima = null;
        for (int i = 0; i < CodigoAcessoService.MAXIMO_TENTATIVAS; i++) {
            ultima = codigoAcessoService.conferir(EMAIL, errado);
        }

        assertEquals(CodigoAcessoService.Resultado.BLOQUEADO, ultima.resultado());
        assertNull(maria.getCodigoAcesso(), "no limite de tentativas o código é descartado");
        // E o código certo não serve mais: é isso que torna a força bruta inviável.
        assertEquals(CodigoAcessoService.Resultado.EXPIRADO, codigoAcessoService.conferir(EMAIL, codigo).resultado());
    }

    @Test
    void conferirCodigoExpiradoNaoAutoriza() {
        String codigo = solicitarECapturarCodigo();
        maria.setCodigoAcessoExpiraEm(LocalDateTime.now().minusMinutes(1));

        CodigoAcessoService.Conferencia conferencia = codigoAcessoService.conferir(EMAIL, codigo);

        assertEquals(CodigoAcessoService.Resultado.EXPIRADO, conferencia.resultado());
        assertNull(maria.getCodigoAcesso());
    }

    @Test
    void conferirSemCodigoPedidoNaoAutoriza() {
        when(assistidoRepository.findByLoginIgnoreCaseAndAcessoAtivoTrue(EMAIL)).thenReturn(Optional.of(maria));

        assertEquals(CodigoAcessoService.Resultado.EXPIRADO,
                codigoAcessoService.conferir(EMAIL, "123456").resultado());
    }

    @Test
    void conferirEmailDesconhecidoNaoAutoriza() {
        when(assistidoRepository.findByLoginIgnoreCaseAndAcessoAtivoTrue("ninguem@exemplo.com"))
                .thenReturn(Optional.empty());

        assertEquals(CodigoAcessoService.Resultado.INVALIDO,
                codigoAcessoService.conferir("ninguem@exemplo.com", "123456").resultado());
    }

    @Test
    void reenviarPelaRecepcaoIgnoraOIntervaloMinimo() {
        solicitarECapturarCodigo();

        // A recepção está com a pessoa na frente; esperar um minuto seria pior que o risco que o
        // intervalo evita.
        assertTrue(codigoAcessoService.reenviarPara(maria));

        verify(emailService, org.mockito.Mockito.times(2))
                .enviarCodigoAcesso(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void reenviarRecusaQuemNaoEntraPorEmail() {
        Assistido semEmail = new Assistido();
        semEmail.setLogin("joao.silva");
        semEmail.setAcessoAtivo(true);

        assertFalse(codigoAcessoService.reenviarPara(semEmail));
        verify(emailService, never()).enviarCodigoAcesso(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void reenviarRecusaAcessoDesativado() {
        maria.setAcessoAtivo(false);

        assertFalse(codigoAcessoService.reenviarPara(maria));
        verify(emailService, never()).enviarCodigoAcesso(anyString(), anyString(), anyString(), anyInt());
    }
}
