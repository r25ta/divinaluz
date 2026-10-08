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
// Desde a V43 há também a entrevista da 1ª sessão e a excepcional (TipoEntrevista): essas não têm
// avaliação, resultado nem tratamento, e não mexem no cartão.
@Getter
@Setter
@ToString
@Entity
public class Entrevista {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Obrigatória só na entrevista de TRATAMENTO (CHECK da V43); as avulsas não seguem uma avaliação.
    @OneToOne
    @JoinColumn(name = "avaliacao_id", unique = true)
    @ToString.Exclude
    private Avaliacao avaliacao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TipoEntrevista tipo = TipoEntrevista.TRATAMENTO;

    @ManyToOne(optional = false)
    @JoinColumn(name = "assistido_id")
    @ToString.Exclude
    private Assistido assistido;

    @DateTimeFormat(pattern = "dd/MM/yyyy")
    private LocalDate data;

    // Nome de quem entrevistou, para exibição (o cartão físico tem o campo "Entrevistador"). Desde a
    // V42 vem do login de quem registrou (entrevistadorResponsavel), não mais digitado.
    private String entrevistador;

    @ManyToOne
    @JoinColumn(name = "entrevistador_id")
    @ToString.Exclude
    private Assistido entrevistadorResponsavel;

    // Novo tratamento ou alta (V42) — o que o Avaliador propôs, confirmado ou ajustado aqui.
    @Enumerated(EnumType.STRING)
    private ResultadoAvaliacao resultado = ResultadoAvaliacao.NOVO_TRATAMENTO;

    // Tratamento decidido nesta entrevista (pode repetir o tratamento anterior). Atualiza
    // Assistido.tratamentoAtual ao ser salva. Nulo quando o resultado é alta.
    @ManyToOne
    @JoinColumn(name = "tratamento_indicado_id")
    @ToString.Exclude
    private TipoTratamento tratamentoIndicado;

    // Observação do entrevistador sobre o tratamento comunicado (V42).
    @Column(columnDefinition = "TEXT")
    private String observacoes;

    public boolean isAlta() {
        return resultado == ResultadoAvaliacao.ALTA;
    }
}
