package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.SessaoTratamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
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

    // Presenças de uma data: como existe no máximo uma sessão de assistência por data, é assim que
    // o painel da sessão acha quem esteve presente (inclusive o que foi lançado pelo prontuário).
    List<SessaoTratamento> findByDataConsulta(LocalDate dataConsulta);

    @Query("select distinct s.assistido.id from SessaoTratamento s "
            + "where s.dataConsulta < :data and s.assistido.id in :assistidoIds")
    Set<Long> findAssistidosComPresencaAntesDe(@Param("data") LocalDate data,
            @Param("assistidoIds") Collection<Long> assistidoIds);
}
