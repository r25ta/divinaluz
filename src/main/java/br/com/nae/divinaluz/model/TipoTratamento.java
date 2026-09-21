package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@Entity
public class TipoTratamento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String codigo;
    private String nome;

    @Column(columnDefinition = "TEXT")
    private String comoFunciona;

    @Column(columnDefinition = "TEXT")
    private String objetivoIndicacao;

    @Column(columnDefinition = "TEXT")
    private String triagemObservacoes;
}
