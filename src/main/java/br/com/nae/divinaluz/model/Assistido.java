package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@ToString
@Getter
@Setter
@Entity
public class Assistido {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private String residencia;
    private Integer idade;
    private String estadoCivil;
    private String sexo;
    private String email;
    private String vinculo; // ASSISTIDO, TRABALHADOR, ALUNO

    @ManyToOne
    @JoinColumn(name = "tratamento_atual_id")
    @ToString.Exclude
    private TipoTratamento tratamentoAtual;

    // Data de início do ciclo de tratamento atual. Reinicia sempre que o assistido fica
    // 3 semanas (21 dias) sem comparecer, o que também zera a contagem de sessões/avaliações
    // usada para disparar a regra das 4 sessões (ver TratamentoService).
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate cicloIniciadoEm;
}