package br.com.nae.divinaluz.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Situação de uma preleção na escala, derivada da data em relação a hoje. Não é persistida:
 * existe só para a consulta da escala mostrar o que já aconteceu, o que acontece nesta semana de
 * assistência (Domingo a Sábado, mesma janela da regra de presença semanal) e o que está agendado.
 */
public enum SituacaoPrelecao {

    ESTA_SEMANA("Esta semana", "dl-badge-teal", "bi-broadcast"),
    AGENDADA("Agendada", "dl-badge-info", "bi-calendar-event"),
    REALIZADA("Realizada", "dl-badge-neutral", "bi-check-circle-fill");

    private final String label;
    private final String badgeClasse;
    private final String icone;

    SituacaoPrelecao(String label, String badgeClasse, String icone) {
        this.label = label;
        this.badgeClasse = badgeClasse;
        this.icone = icone;
    }

    public static SituacaoPrelecao de(LocalDate dataApresentacao, LocalDate hoje) {
        if (dataApresentacao == null || dataApresentacao.isBefore(hoje)) {
            return REALIZADA;
        }
        LocalDate fimDaSemana = hoje.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        return dataApresentacao.isAfter(fimDaSemana) ? AGENDADA : ESTA_SEMANA;
    }

    public String getLabel() {
        return label;
    }

    public String getBadgeClasse() {
        return badgeClasse;
    }

    public String getIcone() {
        return icone;
    }
}
