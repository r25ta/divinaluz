package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/** Um trabalhador escalado numa posição de uma sessão de assistência. */
@Getter
@Setter
@ToString
@Entity
@Table(name = "sessao_escala")
public class EscalaSessao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "sessao_id")
    @ToString.Exclude
    private SessaoAssistencia sessao;

    @ManyToOne(optional = false)
    @JoinColumn(name = "trabalhador_id")
    @ToString.Exclude
    private Trabalhador trabalhador;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PosicaoSessao posicao;
}
