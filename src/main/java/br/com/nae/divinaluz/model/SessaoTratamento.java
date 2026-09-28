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
public class SessaoTratamento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "assistido_id")
    @ToString.Exclude
    private Assistido assistido;

    private Integer numeroSerie;

    private boolean ouvinte;

    @DateTimeFormat(pattern = "dd/MM/yyyy")
    private LocalDate dataConsulta;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prelecao_id")
    @ToString.Exclude
    private Prelecao prelecao;
    
    private boolean visto;
    private boolean assistencia;
    private boolean evangelhoNoLar;
    private boolean leituras;
    private boolean escola;
    private boolean trabalhoEspiritual;
    private boolean medico;
    
    private String observacoes;

    // Getters e Setters...
}