package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Trabalhador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrabalhadorRepository extends JpaRepository<Trabalhador, Long> {
    Optional<Trabalhador> findByAssistidoId(Long assistidoId);
}
