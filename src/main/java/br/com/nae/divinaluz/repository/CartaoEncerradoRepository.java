package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.CartaoEncerrado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CartaoEncerradoRepository extends JpaRepository<CartaoEncerrado, Long> {
    List<CartaoEncerrado> findByAssistidoIdOrderByEncerradoEmDesc(Long assistidoId);
}
