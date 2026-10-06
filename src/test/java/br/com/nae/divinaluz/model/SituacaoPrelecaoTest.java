package br.com.nae.divinaluz.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SituacaoPrelecaoTest {

    // Sexta-feira. A semana de assistência vai de Domingo (20/09) a Sábado (26/09), a mesma janela
    // usada pela regra de uma presença por semana.
    private static final LocalDate HOJE = LocalDate.of(2026, 9, 25);

    @Test
    void dataAnteriorAHojeEhRealizada() {
        assertEquals(SituacaoPrelecao.REALIZADA, SituacaoPrelecao.de(LocalDate.of(2026, 9, 20), HOJE));
        assertEquals(SituacaoPrelecao.REALIZADA, SituacaoPrelecao.de(LocalDate.of(2026, 9, 24), HOJE));
    }

    @Test
    void hojeEORestoDaSemanaContamComoEstaSemana() {
        assertEquals(SituacaoPrelecao.ESTA_SEMANA, SituacaoPrelecao.de(HOJE, HOJE));
        assertEquals(SituacaoPrelecao.ESTA_SEMANA, SituacaoPrelecao.de(LocalDate.of(2026, 9, 26), HOJE));
    }

    @Test
    void domingoSeguinteJaEhDaSemanaSeguinteEntaoAgendada() {
        assertEquals(SituacaoPrelecao.AGENDADA, SituacaoPrelecao.de(LocalDate.of(2026, 9, 27), HOJE));
        assertEquals(SituacaoPrelecao.AGENDADA, SituacaoPrelecao.de(LocalDate.of(2026, 10, 6), HOJE));
    }

    @Test
    void noDomingoAsDuasSessoesDaSemanaContamComoEstaSemana() {
        LocalDate domingo = LocalDate.of(2026, 9, 27);
        assertEquals(SituacaoPrelecao.ESTA_SEMANA, SituacaoPrelecao.de(domingo, domingo));
        // Terça 29/09 cai na mesma semana Domingo–Sábado do domingo 27/09.
        assertEquals(SituacaoPrelecao.ESTA_SEMANA, SituacaoPrelecao.de(LocalDate.of(2026, 9, 29), domingo));
    }

    @Test
    void dataNulaNaoQuebraAClassificacao() {
        assertEquals(SituacaoPrelecao.REALIZADA, SituacaoPrelecao.de((java.time.LocalDate) null, HOJE));
    }
}
