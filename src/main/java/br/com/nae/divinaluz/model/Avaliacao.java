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

    // Data da avaliação em si (livre — não precisa cair no dia de assistência do assistido).
    // A entrevista que comunica o tratamento decidido é um registro separado (ver Entrevista),
    // essa sim amarrada ao dia de assistência.
    @DateTimeFormat(pattern = "dd/MM/yyyy")
    private LocalDate data;

    @Column(columnDefinition = "TEXT")
    private String historico;

    // Recomendações em texto livre do médium para o assistido.
    @Column(columnDefinition = "TEXT")
    private String observacoes;

    @Enumerated(EnumType.STRING)
    private Evolucao evolucao;

    @OneToOne(mappedBy = "avaliacao")
    @ToString.Exclude
    private Entrevista entrevista;
}
