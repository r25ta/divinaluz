package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.service.CelularVinculadoService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * O cookie do celular vinculado ao cartão (ver CelularVinculadoService). Restrito ao caminho
 * {@code /presenca}: é o único lugar que o lê, então ele nem viaja para o resto do sistema.
 *
 * <p>Carrega <strong>um token por pessoa</strong>, separados por ponto (o Base64 de URL não usa
 * ponto): é comum a família dividir um celular, e cada um marca a própria presença por ele.</p>
 *
 * <p>Um ano, renovado a cada uso — quem vem toda semana nunca o vê vencer. (O Chrome limita
 * cookies a 400 dias.)</p>
 */
public final class CookieCelular {

    public static final String NOME = "dl-celular";
    private static final Duration VALIDADE = Duration.ofDays(365);

    private CookieCelular() {
    }

    public static List<String> ler(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        return Arrays.stream(cookies)
                .filter(c -> NOME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .map(valor -> Arrays.stream(valor.split("\\."))
                        .filter(token -> !token.isBlank())
                        .limit(CelularVinculadoService.MAXIMO_DE_PESSOAS)
                        .toList())
                .orElse(List.of());
    }

    /** Grava os tokens (ou apaga o cookie, se não sobrou nenhum). */
    public static void gravar(HttpServletRequest request, HttpServletResponse response, List<String> tokens) {
        if (tokens.isEmpty()) {
            apagar(request, response);
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, montar(request, String.join(".", tokens), VALIDADE).toString());
    }

    public static void apagar(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, montar(request, "", Duration.ZERO).toString());
    }

    private static ResponseCookie montar(HttpServletRequest request, String valor, Duration validade) {
        return ResponseCookie.from(NOME, valor)
                .path(request.getContextPath() + "/presenca")
                .maxAge(validade)
                .httpOnly(true)
                // Atrás do proxy do Render o isSecure vem do X-Forwarded-Proto
                // (server.forward-headers-strategy=framework); em dev, HTTP puro.
                .secure(request.isSecure())
                .sameSite("Lax")
                .build();
    }
}
