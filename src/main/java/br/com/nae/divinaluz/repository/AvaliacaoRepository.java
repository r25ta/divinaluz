package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Avaliacao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AvaliacaoRepository extends JpaRepository<Avaliacao, Long> {
    List<Avaliacao> findByAssistidoIdOrderByDataDesc(Long assistidoId);
    long countByAssistidoId(Long assistidoId);
    long countByAssistidoIdAndDataGreaterThanEqual(Long assistidoId, LocalDate desde);

    // A avaliação mais recente ainda sem entrevista — é ela que a fila de Entrevista (e o
    // check-in/prontuário, quando o cartão está Aguardando Entrevista) linka na ação "Registrar
    // Entrevista". Ordenar por data desc porque, em tese, só deveria existir uma pendente por vez.
    Optional<Avaliacao> findFirstByAssistidoIdAndEntrevistaIsNullOrderByDataDesc(Long assistidoId);
}
