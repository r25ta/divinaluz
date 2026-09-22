package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Entrevista;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface EntrevistaRepository extends JpaRepository<Entrevista, Long> {
    List<Entrevista> findByAssistidoIdOrderByDataDesc(Long assistidoId);
    long countByAssistidoId(Long assistidoId);
    long countByAssistidoIdAndDataGreaterThanEqual(Long assistidoId, LocalDate desde);
    boolean existsByAvaliacaoId(Long avaliacaoId);
}
