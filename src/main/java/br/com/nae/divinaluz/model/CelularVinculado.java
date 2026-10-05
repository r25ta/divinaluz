package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Um celular vinculado ao cartão do assistido, que marca a própria presença pelo QR da sessão sem
 * login (ver CelularVinculadoService). Uma pessoa pode ter mais de um.
 */
@Getter
@Setter
@ToString
@Entity
public class CelularVinculado {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "assistido_id")
    @ToString.Exclude
    private Assistido assistido;

    // SHA-256 do token que fica no cookie do celular; o token mesmo nunca é gravado.
    @ToString.Exclude
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "vinculado_em", nullable = false)
    private LocalDateTime vinculadoEm;

    @Column(name = "ultimo_uso_em")
    private LocalDateTime ultimoUsoEm;
}
