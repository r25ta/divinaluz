package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.TipoTratamento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TipoTratamentoRepository extends JpaRepository<TipoTratamento, Long> {
    Optional<TipoTratamento> findByCodigo(String codigo);
}
