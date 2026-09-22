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
}
