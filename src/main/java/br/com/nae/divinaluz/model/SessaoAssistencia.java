package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

/**
 * Sessão de assistência espiritual (Domingo 08h ou Terça 19h) — o "objeto sessão" do Módulo Sessão
 * (ver CLAUDE.md 3.13). É dona da janela de check-in, da escala de trabalhadores e dos indicadores.
 * A preleção do dia não é FK: é achada pela data na escala de preleções, e a troca emergencial do
 * preletor fica em {@code preletorSubstituto}/{@code temaSubstituto}, sem mexer na escala.
 */
@Getter
@Setter
@ToString
@Entity
@Table(name = "sessao_assistencia")
public class SessaoAssistencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate data;

    @Enumerated(EnumType.STRING)
    @Column(name = "dia_frequencia", nullable = false, length = 20)
    private DiaFrequencia diaFrequencia;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preletor_substituto_id")
    @ToString.Exclude
    private Trabalhador preletorSubstituto;

    @Column(name = "tema_substituto")
    private String temaSubstituto;

    @Column(name = "checkin_aberto_em")
    private LocalDateTime checkinAbertoEm;

    @Column(name = "checkin_fechado_em")
    private LocalDateTime checkinFechadoEm;

    /** Preenchido quando a casa não abriu nesta data (V34). Ver {@link #isCancelada()}. */
    @Column(name = "cancelada_em")
    private LocalDateTime canceladaEm;

    @Column(name = "motivo_cancelamento")
    private String motivoCancelamento;

    @OneToMany(mappedBy = "sessao", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("posicao, id")
    @ToString.Exclude
    private List<EscalaSessao> escala = new ArrayList<>();

    @Transient
    public boolean isCheckinAberto() {
        return checkinAbertoEm != null && checkinFechadoEm == null;
    }

    /**
     * Sessão cancelada: a casa não abriu. Não recebe presença nem abre check-in, e a semana dela
     * não conta como falta na regra dos 21 dias.
     */
    @Transient
    public boolean isCancelada() {
        return canceladaEm != null;
    }

    /** Semana do ano contada de Domingo a Sábado, a mesma janela da regra de presença semanal. */
    @Transient
    public int getSemanaDoAno() {
        return data.get(WeekFields.SUNDAY_START.weekOfWeekBasedYear());
    }
}
