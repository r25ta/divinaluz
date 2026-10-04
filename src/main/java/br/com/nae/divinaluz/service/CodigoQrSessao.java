package br.com.nae.divinaluz.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Código do QR da sessão, que <strong>muda a cada minuto</strong> (2026-10-04). O QR fica na tela da
 * recepção e o assistido o escaneia com o celular para marcar a própria presença; se o código fosse
 * fixo, uma foto dele mandada por mensagem serviria para marcar presença de casa.
 *
 * <p>O código é um HMAC-SHA256 do segredo da sessão ({@code SessaoAssistencia.checkinSegredo}) com o
 * número do minuto, truncado. Não precisa ser guardado: o servidor recalcula. Vale por
 * {@link #MINUTOS_VALIDOS} minutos — o bastante para escanear, entrar com o login se o celular pedir
 * e confirmar, e pouco demais para a foto circular.</p>
 */
public final class CodigoQrSessao {

    /** Minuto atual e os anteriores: entre escanear e confirmar pode haver um login no meio. */
    public static final int MINUTOS_VALIDOS = 5;
    private static final int TAMANHO = 16;
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private CodigoQrSessao() {
    }

    /** Segredo novo para uma sessão (32 bytes aleatórios, em hexadecimal). */
    public static String novoSegredo() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String codigo(String segredo, Instant agora) {
        return codigoDoMinuto(segredo, agora.getEpochSecond() / 60);
    }

    /** Confere o código contra o minuto atual e os {@link #MINUTOS_VALIDOS} - 1 anteriores. */
    public static boolean valido(String segredo, String codigo, Instant agora) {
        if (segredo == null || codigo == null || codigo.length() != TAMANHO) {
            return false;
        }
        long minuto = agora.getEpochSecond() / 60;
        byte[] recebido = codigo.getBytes(StandardCharsets.US_ASCII);
        boolean confere = false;
        for (int k = 0; k < MINUTOS_VALIDOS; k++) {
            // Sem atalho no primeiro acerto e com comparação de tempo constante: o tempo de resposta
            // não dá pista de quão perto um código chutado chegou.
            byte[] esperado = codigoDoMinuto(segredo, minuto - k).getBytes(StandardCharsets.US_ASCII);
            confere |= MessageDigest.isEqual(esperado, recebido);
        }
        return confere;
    }

    private static String codigoDoMinuto(String segredo, long minuto) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] assinatura = mac.doFinal(Long.toString(minuto).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(assinatura).substring(0, TAMANHO);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponível.", e);
        }
    }
}
