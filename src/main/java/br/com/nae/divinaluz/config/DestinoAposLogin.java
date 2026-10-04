package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.Assistido;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * Para onde vai quem acabou de entrar — pelas DUAS formas de login (senha e código de e-mail), para
 * não divergirem. A regra é a de 2026-10-04: todo mundo entra pelo próprio cartão de tratamento.
 *
 * <p>Exceção única: quem escaneou o QR da sessão ({@code /presenca/...}) sem estar logado. O Spring
 * guarda esse pedido antes de mandar ao login, e aqui a pessoa volta para ele — senão ela entraria,
 * cairia no cartão e teria de escanear de novo, talvez já com o código vencido. Só essa rota é
 * devolvida de propósito: devolver qualquer pedido guardado reabriria o problema que levou ao "todo
 * mundo pelo cartão" (cair numa tela que a pessoa não alcança).</p>
 */
public final class DestinoAposLogin {

    private static final RequestCache PEDIDOS_GUARDADOS = new HttpSessionRequestCache();

    private DestinoAposLogin() {
    }

    /** URL de destino: o pedido de presença guardado ou, no geral, o próprio cartão. */
    public static String url(HttpServletRequest request, HttpServletResponse response, Assistido logado) {
        SavedRequest guardado = PEDIDOS_GUARDADOS.getRequest(request, response);
        if (guardado != null && guardado.getRedirectUrl().contains(request.getContextPath() + "/presenca/")) {
            PEDIDOS_GUARDADOS.removeRequest(request, response);
            return guardado.getRedirectUrl();
        }
        return request.getContextPath() + "/prontuario/" + logado.getId() + "/cartao";
    }
}
