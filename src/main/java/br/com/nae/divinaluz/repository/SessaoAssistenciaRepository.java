package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.SessaoAssistencia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SessaoAssistenciaRepository extends JpaRepository<SessaoAssistencia, Long> {
    Optional<SessaoAssistencia> findByData(LocalDate data);
    List<SessaoAssistencia> findAllByOrderByDataDesc();

    // Janela de check-in aberta: a abertura só é permitida na própria data e fecha a janela
    // esquecida de outra data, então na prática isto devolve no máximo uma linha.
    Optional<SessaoAssistencia> findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull();
}
