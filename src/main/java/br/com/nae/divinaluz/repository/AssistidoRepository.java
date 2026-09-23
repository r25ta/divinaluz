package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Assistido;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssistidoRepository extends JpaRepository<Assistido, Long> {
    List<Assistido> findByAtivo(boolean ativo);
    List<Assistido> findAllByOrderByNomeAsc();
}