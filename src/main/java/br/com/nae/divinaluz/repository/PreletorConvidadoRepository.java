package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.PreletorConvidado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PreletorConvidadoRepository extends JpaRepository<PreletorConvidado, Long> {
    List<PreletorConvidado> findAllByOrderByNomeAsc();

    List<PreletorConvidado> findByAtivoTrueOrderByNomeAsc();
}
