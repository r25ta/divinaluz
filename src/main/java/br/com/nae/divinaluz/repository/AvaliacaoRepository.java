package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Avaliacao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AvaliacaoRepository extends JpaRepository<Avaliacao, Long> {
    List<Avaliacao> findByAssistidoIdOrderByDataDesc(Long assistidoId);
    long countByAssistidoId(Long assistidoId);
    long countByAssistidoIdAndDataGreaterThanEqual(Long assistidoId, LocalDate desde);
}
