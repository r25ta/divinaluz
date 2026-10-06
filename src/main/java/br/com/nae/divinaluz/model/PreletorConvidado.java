package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Preletor convidado (V40): faz preleção na casa, mas não é assistido nem trabalhador — não tem
 * cartão, prontuário nem acesso ao sistema. Ver PrelecaoController.
 */
@Getter
@Setter
@ToString
@Entity
public class PreletorConvidado {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String nome;

    @Column(length = 30)
    private String telefone;

    @Column(length = 150)
    private String email;

    /** Casa espírita ou instituição de onde vem. */
    @Column(length = 150)
    private String origem;

    @Column(columnDefinition = "TEXT")
    private String observacoes;

    @Column(nullable = false)
    private boolean ativo = true;

    @Column(name = "criado_em", nullable = false)
    private LocalDateTime criadoEm = LocalDateTime.now();
}
