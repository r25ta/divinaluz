package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;

/**
 * Registro de um ciclo de tratamento (cartão) já encerrado — o "histórico de cartões" do item 7 da
 * seção 8 do CLAUDE.md. Antes disso, reiniciar o cartão só avançava {@code cicloIniciadoEm} e o
 * ciclo anterior sumia; agora cada encerramento vira uma linha aqui, com o tratamento daquele
 * ciclo, o período, quantas presenças efetivas teve e <em>como</em> terminou
 * ({@link StatusCartaoEncerrado} — em especial "Tratamento Incompleto" quando expirou por tempo).
 *
 * <p>É gravado pelo {@link br.com.nae.divinaluz.service.TratamentoService} nos três pontos em que
 * um ciclo fecha: reinício por 21 dias, Entrevista (que abre o ciclo seguinte) e troca manual de
 * tratamento.
 */
@ToString
@Getter
@Setter
@Entity
@Table(name = "cartao_encerrado")
public class CartaoEncerrado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "assistido_id", nullable = false)
    @ToString.Exclude
    private Assistido assistido;

    /** Tratamento que valia nesse ciclo (pode ser nulo em cadastros antigos sem tratamento). */
    @ManyToOne
    @JoinColumn(name = "tratamento_id")
    @ToString.Exclude
    private TipoTratamento tratamento;

    @Column(name = "iniciado_em")
    private LocalDate iniciadoEm;

    @Column(name = "encerrado_em", nullable = false)
    private LocalDate encerradoEm;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_final", nullable = false, length = 40)
    private StatusCartaoEncerrado statusFinal;

    /** Presenças efetivas (sem ouvintes) contabilizadas no ciclo. */
    @Column(name = "sessoes_efetivas", nullable = false)
    private int sessoesEfetivas;
}
