package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    // 1ª vez, 2ª vez, ... sem limite: o cartão físico para na 8ª só por falta de espaço no papel.
    private Integer numeroVez;

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

    // Novo tratamento ou alta (V42). Com alta não há tratamento proposto.
    @Enumerated(EnumType.STRING)
    private ResultadoAvaliacao resultado = ResultadoAvaliacao.NOVO_TRATAMENTO;

    // Novo tratamento proposto pelo Avaliador (V36). Enquanto o cartão aguarda a entrevista, só quem
    // entrevista o enxerga (desde 2026-10-07); a Entrevista abre com ele já escolhido.
    @ManyToOne
    @JoinColumn(name = "tratamento_proposto_id")
    @ToString.Exclude
    private TipoTratamento tratamentoProposto;

    // "Resultado da Consulta" do verso do cartão físico (V42): recomendações a seguir durante o
    // próximo tratamento. O texto livre complementar fica em observacoes.
    @Column(name = "rec_visto")
    private boolean recVisto;
    @Column(name = "rec_assistencia")
    private boolean recAssistencia;
    @Column(name = "rec_evangelho_no_lar")
    private boolean recEvangelhoNoLar;
    @Column(name = "rec_leituras")
    private boolean recLeituras;
    @Column(name = "rec_escola")
    private boolean recEscola;
    @Column(name = "rec_trabalho_espiritual")
    private boolean recTrabalhoEspiritual;
    @Column(name = "rec_medico")
    private boolean recMedico;

    // Quem registrou (V42): o login de quem salvou, nunca um nome digitado. Nulo nas antigas.
    @ManyToOne
    @JoinColumn(name = "avaliador_id")
    @ToString.Exclude
    private Assistido avaliador;

    @OneToOne(mappedBy = "avaliacao")
    @ToString.Exclude
    private Entrevista entrevista;

    public boolean isAlta() {
        return resultado == ResultadoAvaliacao.ALTA;
    }

    /** Os rótulos das recomendações marcadas, na ordem do verso do cartão físico. */
    public List<String> getRecomendacoes() {
        List<String> marcadas = new ArrayList<>();
        if (recVisto) marcadas.add("Visto");
        if (recAssistencia) marcadas.add("Assistência");
        if (recEvangelhoNoLar) marcadas.add("Evangelho no Lar");
        if (recLeituras) marcadas.add("Leituras");
        if (recEscola) marcadas.add("Escola");
        if (recTrabalhoEspiritual) marcadas.add("Trabalho Espiritual");
        if (recMedico) marcadas.add("Médico");
        return marcadas;
    }
}
