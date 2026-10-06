package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@Entity
public class Prelecao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @DateTimeFormat(pattern = "dd/MM/yyyy")
    @Column(name = "data_apresentacao", nullable = false)
    private LocalDate dataApresentacao;

    @Column(nullable = false)
    private String tema;

    /** Preletor da casa. Exatamente um entre este e {@link #convidado} (V40). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trabalhador_id")
    @ToString.Exclude
    private Trabalhador preletor;

    /** Preletor convidado, que não é assistido (V40). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "convidado_id")
    @ToString.Exclude
    private PreletorConvidado convidado;

    /** Cancelada: não vai acontecer, mas fica na escala como registro e libera a data (V40). */
    @Column(name = "cancelada_em")
    private LocalDateTime canceladaEm;

    @Column(name = "motivo_cancelamento")
    private String motivoCancelamento;

    // As colunas checkin_aberto_em/checkin_fechado_em da tabela ficaram sem uso desde a V27: a janela
    // de check-in passou para a SessaoAssistencia (a preleção deixou de ser a "sessão do dia").

    /** Nome de quem faz a preleção, seja trabalhador da casa ou convidado. */
    @Transient
    public String getNomePreletor() {
        if (convidado != null) {
            return convidado.getNome();
        }
        return preletor != null && preletor.getAssistido() != null ? preletor.getAssistido().getNome() : null;
    }

    @Transient
    public boolean isPreletorConvidado() {
        return convidado != null;
    }

    @Transient
    public boolean isCancelada() {
        return canceladaEm != null;
    }
}
