package br.com.nae.divinaluz.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@Entity
public class Prelecao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @Column(name = "data_apresentacao", nullable = false)
    private LocalDate dataApresentacao;

    @Column(nullable = false)
    private String tema;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trabalhador_id", nullable = false)
    @ToString.Exclude
    private Trabalhador preletor;

    // Janela de check-in por QR code: a recepção abre no início da assistência e fecha no fim. O QR
    // do cartão só carimba presença enquanto a janela da preleção daquela data estiver aberta.
    @Column(name = "checkin_aberto_em")
    private LocalDateTime checkinAbertoEm;

    @Column(name = "checkin_fechado_em")
    private LocalDateTime checkinFechadoEm;

    @Transient
    public boolean isCheckinAberto() {
        return checkinAbertoEm != null && checkinFechadoEm == null;
    }
}
