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
    /** Há presença ligada a esta preleção? Então ela não pode ser excluída (só cancelada). */
    boolean existsByPrelecaoId(Long prelecaoId);

    List<SessaoTratamento> findByAssistidoIdOrderByDataConsultaDesc(Long assistidoId);
    long countByAssistidoId(Long assistidoId);
    Optional<SessaoTratamento> findFirstByAssistidoIdAndOuvinteFalseOrderByDataConsultaDesc(Long assistidoId);
    long countByAssistidoIdAndOuvinteFalse(Long assistidoId);
    long countByAssistidoIdAndOuvinteFalseAndDataConsultaGreaterThanEqual(Long assistidoId, LocalDate desde);

    // Presenças de uma data: como existe no máximo uma sessão de assistência por data, é assim que
    // o painel da sessão acha quem esteve presente (inclusive o que foi lançado pelo prontuário).
    List<SessaoTratamento> findByDataConsulta(LocalDate dataConsulta);

    // Listagem de Prontuário (2026-11-02): a última presença de cada pessoa (efetiva ou ouvinte),
    // numa consulta só — [assistidoId, data].
    @Query("select s.assistido.id, max(s.dataConsulta) from SessaoTratamento s group by s.assistido.id")
    List<Object[]> ultimaPresencaPorAssistido();

    // ...e as presenças do ciclo em andamento de todos, para a consulta rápida da recepção.
    @Query("select s from SessaoTratamento s join s.assistido a left join fetch s.prelecao "
            + "where a.cicloIniciadoEm is not null and s.dataConsulta >= a.cicloIniciadoEm "
            + "order by s.dataConsulta desc, s.id desc")
    List<SessaoTratamento> presencasDosCiclosEmAndamento();

    @Query("select distinct s.assistido.id from SessaoTratamento s "
            + "where s.dataConsulta < :data and s.assistido.id in :assistidoIds")
    Set<Long> findAssistidosComPresencaAntesDe(@Param("data") LocalDate data,
            @Param("assistidoIds") Collection<Long> assistidoIds);
}
