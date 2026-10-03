package br.com.nae.divinaluz.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Página do 403, no lugar da "Whitelabel Error Page" (2026-10-04). O {@code SecurityConfig} encaminha
 * para cá qualquer acesso negado, e há dois casos bem diferentes para quem está do outro lado:
 *
 * <ul>
 *   <li><strong>Página desatualizada</strong> ({@link CsrfException}): o formulário foi aberto antes de
 *       um novo login neste navegador — outra pessoa entrou em outra aba, ou a pessoa entrou de novo
 *       depois de um deploy/hibernação e voltou ao formulário antigo. O token de proteção do formulário
 *       era da sessão anterior. Nada foi gravado; basta recarregar e enviar de novo. Era o 403 "sem
 *       explicação" que aparecia ao salvar.</li>
 *   <li><strong>Sem permissão</strong>: a função da pessoa não alcança aquele módulo (ver 3.17).</li>
 * </ul>
 *
 * <p>Atende qualquer método porque chega por <em>forward</em> do próprio pedido que foi negado, que
 * em geral é um POST.</p>
 */
@Controller
public class AcessoNegadoController {

    @RequestMapping("/acesso-negado")
    public String acessoNegado(HttpServletRequest request, HttpServletResponse response, Model model) {
        Object motivo = request.getAttribute(WebAttributes.ACCESS_DENIED_403);
        model.addAttribute("paginaDesatualizada", motivo instanceof CsrfException);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return "acesso-negado";
    }
}
