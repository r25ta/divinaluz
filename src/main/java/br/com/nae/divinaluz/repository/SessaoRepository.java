package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.SessaoTratamento;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SessaoRepository extends JpaRepository<SessaoTratamento, Long> {
    List<SessaoTratamento> findByAssistidoIdOrderByDataConsultaDesc(Long assistidoId);
    Optional<SessaoTratamento> findFirstByAssistidoIdOrderByDataConsultaDesc(Long assistidoId);
    long countByAssistidoId(Long assistidoId);
    long countByAssistidoIdAndDataConsultaGreaterThanEqual(Long assistidoId, LocalDate desde);
    Optional<SessaoTratamento> findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(Long assistidoId);
    long countByAssistidoIdAndOuvinteFalse(Long assistidoId);
    long countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(Long assistidoId, LocalDate desde);
}
