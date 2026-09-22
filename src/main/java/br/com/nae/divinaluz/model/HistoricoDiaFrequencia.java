package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@Entity
public class HistoricoDiaFrequencia {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "assistido_id")
    @ToString.Exclude
    private Assistido assistido;

    @Enumerated(EnumType.STRING)
    private DiaFrequencia diaAnterior;

    @Enumerated(EnumType.STRING)
    private DiaFrequencia diaNovo;

    private LocalDateTime dataHora;

    @Column(columnDefinition = "TEXT")
    private String motivo;
}
