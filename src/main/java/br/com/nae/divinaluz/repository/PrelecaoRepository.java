package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Prelecao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PrelecaoRepository extends JpaRepository<Prelecao, Long> {
    List<Prelecao> findAllByOrderByDataApresentacaoAsc();
    Optional<Prelecao> findByDataApresentacao(LocalDate dataApresentacao);

}
