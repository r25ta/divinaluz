package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

// Após a avaliação, o assistido passa por uma entrevista em que o entrevistador comunica o
// tratamento decidido. Ao contrário da avaliação, a data da entrevista precisa cair no dia de
// assistência do assistido (ver TratamentoService.validarDiaDaSemana).
@Getter
@Setter
@ToString
@Entity
public class Entrevista {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "avaliacao_id", unique = true)
    @ToString.Exclude
    private Avaliacao avaliacao;

    @ManyToOne(optional = false)
    @JoinColumn(name = "assistido_id")
    @ToString.Exclude
    private Assistido assistido;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate data;

    private String entrevistador;

    // Tratamento decidido nesta entrevista (pode repetir o tratamento anterior). Atualiza
    // Assistido.tratamentoAtual ao ser salva.
    @ManyToOne
    @JoinColumn(name = "tratamento_indicado_id")
    @ToString.Exclude
    private TipoTratamento tratamentoIndicado;
}
