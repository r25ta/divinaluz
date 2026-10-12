package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Trabalhador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TrabalhadorRepository extends JpaRepository<Trabalhador, Long> {
    Optional<Trabalhador> findByAssistidoId(Long assistidoId);

    /** Os trabalhadores com as funções numa consulta só — para o selo de cada linha da listagem (2026-10-11). */
    @Query("select distinct t from Trabalhador t join fetch t.assistido left join fetch t.funcoes")
    List<Trabalhador> comFuncoes();
}
