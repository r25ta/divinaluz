package br.com.nae.divinaluz.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O QR da sessão muda a cada minuto e vale por alguns minutos — o que impede a "foto no WhatsApp". */
class CodigoQrSessaoTest {

    private static final String SEGREDO = CodigoQrSessao.novoSegredo();
    private static final Instant AGORA = Instant.parse("2026-10-04T11:00:30Z");

    @Test
    void codigoMudaACadaMinuto() {
        assertNotEquals(CodigoQrSessao.codigo(SEGREDO, AGORA), CodigoQrSessao.codigo(SEGREDO, AGORA.plusSeconds(60)));
        assertEquals(CodigoQrSessao.codigo(SEGREDO, AGORA), CodigoQrSessao.codigo(SEGREDO, AGORA.plusSeconds(20)));
    }

    @Test
    void codigoValeEnquantoDuraAJanelaDeMinutos() {
        String codigo = CodigoQrSessao.codigo(SEGREDO, AGORA);
        assertTrue(CodigoQrSessao.valido(SEGREDO, codigo, AGORA));
        assertTrue(CodigoQrSessao.valido(SEGREDO, codigo, AGORA.plusSeconds(60L * (CodigoQrSessao.MINUTOS_VALIDOS - 1))),
                "tempo para escanear, entrar com o login e confirmar");
        assertFalse(CodigoQrSessao.valido(SEGREDO, codigo, AGORA.plusSeconds(60L * CodigoQrSessao.MINUTOS_VALIDOS)),
                "uma foto antiga do QR não serve mais");
    }

    @Test
    void codigoDeOutraSessaoOuAdulteradoNaoVale() {
        String codigo = CodigoQrSessao.codigo(SEGREDO, AGORA);
        assertFalse(CodigoQrSessao.valido(CodigoQrSessao.novoSegredo(), codigo, AGORA), "outra sessão");
        assertFalse(CodigoQrSessao.valido(SEGREDO, codigo.substring(1) + "A", AGORA), "adulterado");
        assertFalse(CodigoQrSessao.valido(SEGREDO, "curto", AGORA));
        assertFalse(CodigoQrSessao.valido(null, codigo, AGORA), "sessão sem segredo (nunca aberta)");
    }
}
