package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.HistoricoDiaFrequencia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HistoricoDiaFrequenciaRepository extends JpaRepository<HistoricoDiaFrequencia, Long> {
    List<HistoricoDiaFrequencia> findByAssistidoIdOrderByDataHoraDesc(Long assistidoId);
}
