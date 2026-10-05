package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.CelularVinculado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CelularVinculadoRepository extends JpaRepository<CelularVinculado, Long> {
    Optional<CelularVinculado> findByTokenHash(String tokenHash);

    long countByAssistidoId(Long assistidoId);

    long deleteByAssistidoId(Long assistidoId);
}
