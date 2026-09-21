package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Getter
@Setter
@ToString
@Entity
public class Avaliacao {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "assistido_id")
    @ToString.Exclude
    private Assistido assistido;

    private Integer numeroVez; // 1ª vez, 2ª vez, ... (1 a 8)

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate data;

    private String entrevistador;

    @Column(columnDefinition = "TEXT")
    private String historico;

    @Enumerated(EnumType.STRING)
    private Evolucao evolucao;

    // Resultado da entrevista: tratamento decidido para o assistido a partir desta avaliação
    // (pode repetir o tratamento anterior). Atualiza Assistido.tratamentoAtual ao ser salva.
    @ManyToOne
    @JoinColumn(name = "tratamento_indicado_id")
    @ToString.Exclude
    private TipoTratamento tratamentoIndicado;
}
