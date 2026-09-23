package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class Usuario {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String login;

    @Column(length = 255)
    private String email;

    @Column(nullable = false)
    private String senha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProvedorIdentidade provedor = ProvedorIdentidade.LOCAL;

    @Column(name = "identificador_externo", length = 255)
    private String identificadorExterno;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PerfilAcesso perfil;

    @OneToOne
    @JoinColumn(name = "assistido_id", unique = true)
    private Assistido assistido;

    @Column(nullable = false)
    private boolean ativo = true;
}