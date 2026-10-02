package br.com.nae.divinaluz.model;

import java.time.DayOfWeek;
import java.time.LocalTime;

public enum DiaFrequencia {

    TERCA_19H(DayOfWeek.TUESDAY, LocalTime.of(19, 0), "Terça-feira, 19h"),
    DOMINGO_08H(DayOfWeek.SUNDAY, LocalTime.of(8, 0), "Domingo, 08h");

    private final DayOfWeek diaSemana;
    private final LocalTime horario;
    private final String label;

    DiaFrequencia(DayOfWeek diaSemana, LocalTime horario, String label) {
        this.diaSemana = diaSemana;
        this.horario = horario;
        this.label = label;
    }

    public DayOfWeek getDiaSemana() {
        return diaSemana;
    }

    public LocalTime getHorario() {
        return horario;
    }

    public String getLabel() {
        return label;
    }

    /**
     * O dia da semana no índice que o JavaScript usa (0 = domingo … 6 = sábado), para o calendário
     * dos formulários liberar só este dia ({@code data-dias} em data-picker.js).
     *
     * <p>Converte do padrão ISO do {@link DayOfWeek} (1 = segunda … 7 = domingo) com {@code % 7}:
     * terça 2 → 2, domingo 7 → 0. Existe aqui, e não no template, porque é conversão de calendário e
     * não apresentação — e porque errar isso desloca o calendário em um dia sem nenhum sintoma
     * visível além de a data certa aparecer desabilitada.</p>
     */
    public int getIndiceJs() {
        return diaSemana.getValue() % 7;
    }
}
