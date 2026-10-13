package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.exception.RegistroNaoEncontradoException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

/**
 * O que escapa dos controllers sem tratamento (2026-10-13). Antes, tudo virava "Algo deu errado"
 * (500): um link para um cadastro que não existe, uma regra recusada numa rota que não a esperava, ou
 * duas recepções gravando a mesma coisa ao mesmo tempo. Agora cada caso tem o código certo e uma
 * explicação na própria página de erro ({@code error.html}):
 *
 * <ul>
 *   <li><strong>404</strong> — o registro da URL não existe ({@link RegistroNaoEncontradoException});</li>
 *   <li><strong>409</strong> — outra pessoa gravou o mesmo dado no mesmo instante (índice único, trava do
 *       banco): nada foi gravado, basta voltar e tentar de novo;</li>
 *   <li><strong>422</strong> — uma regra recusou o pedido, com a mensagem da regra.</li>
 * </ul>
 *
 * <p>Os controllers que já tratam essas exceções (a maioria, devolvendo a mensagem na própria tela)
 * continuam tratando: isto só pega o que sobra. Pedidos que não são de página (imagens dos QR) recebem
 * só o código, sem HTML.</p>
 */
@ControllerAdvice
public class TratamentoDeErros {

    private static final Logger log = LoggerFactory.getLogger(TratamentoDeErros.class);

    @ExceptionHandler(RegistroNaoEncontradoException.class)
    public void naoEncontrado(RegistroNaoEncontradoException e, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        log.debug("{} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        response.sendError(HttpStatus.NOT_FOUND.value());
    }

    @ExceptionHandler({ DataIntegrityViolationException.class, PessimisticLockingFailureException.class })
    public ModelAndView conflito(DataAccessException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // Só o tipo: a mensagem do banco repete o valor recusado (um e-mail, um login), que é dado pessoal.
        log.warn("Conflito ao gravar em {} {}: {}", request.getMethod(), request.getRequestURI(),
                NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName());
        return pagina(request, response, HttpStatus.CONFLICT,
                "Outra pessoa gravou a mesma informação neste mesmo instante, então nada foi gravado agora. "
                        + "Volte, recarregue a página e confira antes de tentar de novo.");
    }

    @ExceptionHandler(RegraNegocioException.class)
    public ModelAndView regraRecusada(RegraNegocioException e, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        return pagina(request, response, HttpStatus.valueOf(422), e.getMessage());
    }

    private static ModelAndView pagina(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String mensagem) throws IOException {
        if (!aceitaHtml(request)) {
            response.sendError(status.value());
            return null;
        }
        ModelAndView pagina = new ModelAndView("error");
        pagina.setStatus(status);
        pagina.addObject("status", status.value());
        pagina.addObject("mensagem", mensagem);
        return pagina;
    }

    private static boolean aceitaHtml(HttpServletRequest request) {
        String aceita = request.getHeader("Accept");
        return !request.getRequestURI().endsWith(".png")
                && (aceita == null || aceita.contains("text/html") || aceita.contains("*/*"));
    }
}
