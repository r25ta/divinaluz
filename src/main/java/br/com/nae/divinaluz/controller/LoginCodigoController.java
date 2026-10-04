package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.config.AutoridadesAssistido;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.service.CodigoAcessoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Entrada sem senha, em duas telas: informe o e-mail → digite o código de 6 dígitos que chegou nele.
 * As regras (validade, tentativas, hash, intervalo entre pedidos) ficam todas no
 * {@link CodigoAcessoService}; aqui só há o vai-e-vem das telas e a autenticação em si.
 *
 * <p>O e-mail viaja entre as duas telas pela <strong>sessão</strong>, não por campo escondido: assim
 * não dá para conferir um código contra um e-mail diferente do que foi pedido.</p>
 */
@Controller
public class LoginCodigoController {

    /** Mesma mensagem para e-mail cadastrado e desconhecido — ver CodigoAcessoService. */
    private static final String MENSAGEM_NEUTRA =
            "Se este e-mail estiver cadastrado, enviamos um código de 6 dígitos para ele.";

    private static final String SESSAO_EMAIL = "entrar.email";

    private final CodigoAcessoService codigoAcessoService;
    private final AutoridadesAssistido autoridades;
    private final RememberMeServices rememberMeServices;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public LoginCodigoController(CodigoAcessoService codigoAcessoService, AutoridadesAssistido autoridades,
            RememberMeServices rememberMeServices) {
        this.codigoAcessoService = codigoAcessoService;
        this.autoridades = autoridades;
        this.rememberMeServices = rememberMeServices;
    }

    @GetMapping("/entrar")
    public String formularioEmail() {
        return "entrar-email";
    }

    @PostMapping("/entrar")
    public String enviarCodigo(@RequestParam String email, HttpSession sessao, Model model) {
        codigoAcessoService.solicitar(email);
        sessao.setAttribute(SESSAO_EMAIL, email == null ? "" : email.trim());
        model.addAttribute("email", email);
        model.addAttribute("aviso", MENSAGEM_NEUTRA);
        return "entrar-codigo";
    }

    @GetMapping("/entrar/codigo")
    public String formularioCodigo(HttpSession sessao, Model model) {
        String email = (String) sessao.getAttribute(SESSAO_EMAIL);
        if (email == null || email.isBlank()) {
            return "redirect:/entrar";
        }
        model.addAttribute("email", email);
        return "entrar-codigo";
    }

    @PostMapping("/entrar/codigo")
    public String conferirCodigo(@RequestParam String codigo, HttpSession sessao, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        String email = (String) sessao.getAttribute(SESSAO_EMAIL);
        if (email == null || email.isBlank()) {
            return "redirect:/entrar";
        }

        CodigoAcessoService.Conferencia conferencia = codigoAcessoService.conferir(email, codigo);
        if (!conferencia.ok()) {
            model.addAttribute("email", email);
            model.addAttribute("erro", switch (conferencia.resultado()) {
                case EXPIRADO -> "Esse código expirou. Peça um novo.";
                case BLOQUEADO -> "Muitas tentativas erradas. Peça um código novo.";
                default -> "Código incorreto. Confira o e-mail e tente de novo.";
            });
            // Pedir um novo código volta para a primeira tela, então o e-mail sai da sessão nos dois
            // casos em que o código morreu — não há mais o que conferir contra ele.
            if (conferencia.resultado() != CodigoAcessoService.Resultado.INVALIDO) {
                sessao.removeAttribute(SESSAO_EMAIL);
                model.addAttribute("pedirNovo", true);
            }
            return "entrar-codigo";
        }

        Assistido assistido = conferencia.assistido();
        autenticar(assistido, request, response);
        sessao.removeAttribute(SESSAO_EMAIL);

        // Mesmo destino do login por senha: todo mundo entra pelo próprio cartão (ver SecurityConfig).
        return "redirect:/prontuario/" + assistido.getId() + "/cartao";
    }

    /**
     * Autentica sem passar pelo filtro do formulário (não há senha para conferir). As autoridades vêm
     * do mesmo {@link AutoridadesAssistido} que o login por senha usa, para um perfil não alcançar
     * módulos diferentes dependendo de como entrou.
     */
    private void autenticar(Assistido assistido, HttpServletRequest request, HttpServletResponse response) {
        // O principal precisa ser um UserDetails, e não o login em String: os controllers recebem
        // @AuthenticationPrincipal UserDetails, que chegaria nulo com um principal de outro tipo.
        UserDetails usuario = autoridades.usuario(assistido);
        Authentication autenticacao = UsernamePasswordAuthenticationToken.authenticated(
                usuario, null, usuario.getAuthorities());

        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(autenticacao);
        SecurityContextHolder.setContext(contexto);
        // Sem gravar no repositório a autenticação morreria no fim desta requisição, e o redirect
        // seguinte cairia de volta no login.
        contextRepository.saveContext(contexto, request, response);

        // Só cria o cookie se o checkbox "lembrar deste aparelho" vier marcado: o próprio
        // RememberMeServices lê o parâmetro do request (ver SecurityConfig.PARAMETRO_LEMBRAR).
        rememberMeServices.loginSuccess(request, response, autenticacao);
    }
}
