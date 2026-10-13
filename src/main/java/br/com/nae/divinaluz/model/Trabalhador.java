package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@ToString
@Entity
public class Trabalhador {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "assistido_id", nullable = false, unique = true)
    @ToString.Exclude
    private Assistido assistido;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "trabalhador_funcao", joinColumns = @JoinColumn(name = "trabalhador_id"))
    @Column(name = "funcao")
    @Enumerated(EnumType.STRING)
    private Set<TipoTrabalhador> funcoes = new LinkedHashSet<>();

    /**
     * Se a pessoa exerce a função. O Dirigente acumula todos os perfis (2026-10-12): desde então ele é
     * gravado só como Dirigente, e é por aqui — e não por {@code getFuncoes().contains} — que se
     * pergunta, por exemplo, se ele pode ser o preletor de uma preleção.
     */
    public boolean exerce(TipoTrabalhador funcao) {
        return funcoes != null && (funcoes.contains(funcao) || funcoes.contains(TipoTrabalhador.DIRIGENTE));
    }
}
