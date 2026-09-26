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
public class Prelecao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @Column(name = "data_apresentacao", nullable = false)
    private LocalDate dataApresentacao;

    @Column(nullable = false)
    private String tema;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trabalhador_id", nullable = false)
    @ToString.Exclude
    private Trabalhador preletor;

    // As colunas checkin_aberto_em/checkin_fechado_em da tabela ficaram sem uso desde a V27: a janela
    // de check-in passou para a SessaoAssistencia (a preleção deixou de ser a "sessão do dia").
}
