package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.Period;

@ToString
@Getter
@Setter
@Entity
public class Assistido {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private String residencia;
    private String cep;
    private String endereco;
    private String numero;
    private String complemento;
    private String bairro;
    private String cidade;
    private String uf;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dataNascimento;

    private String estadoCivil;
    private String sexo;
    private String email;
    private String vinculo; // ASSISTIDO, TRABALHADOR, ALUNO

    // Dia da semana em que o assistido comparece à casa espírita (terça 19h ou domingo 08h).
    // Só é alterado via rota dedicada (ver HistoricoDiaFrequencia), nunca pelo formulário de
    // cadastro/edição geral, para garantir que toda mudança fique registrada no histórico.
    @Enumerated(EnumType.STRING)
    private DiaFrequencia diaFrequencia;

    @ManyToOne
    @JoinColumn(name = "tratamento_atual_id")
    @ToString.Exclude
    private TipoTratamento tratamentoAtual;

    // Data de início do ciclo de tratamento atual. Reinicia sempre que o assistido fica
    // 3 semanas (21 dias) sem comparecer, o que também zera a contagem de sessões/avaliações
    // usada para disparar a regra das 4 sessões (ver TratamentoService).
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate cicloIniciadoEm;

    // Exclusão lógica: o assistido pode querer retomar tratamento futuramente, então o
    // prontuário nunca é removido de fato, apenas desativado (some da listagem principal).
    private boolean ativo = true;

    @Transient
    public Integer getIdade() {
        return dataNascimento != null ? Period.between(dataNascimento, LocalDate.now()).getYears() : null;
    }
}