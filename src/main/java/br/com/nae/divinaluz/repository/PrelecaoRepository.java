package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Prelecao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PrelecaoRepository extends JpaRepository<Prelecao, Long> {
    List<Prelecao> findAllByOrderByDataApresentacaoAsc();
    Optional<Prelecao> findByDataApresentacao(LocalDate dataApresentacao);

    // Janela de check-in aberta: por regra existe no máximo uma preleção por data e a abertura só é
    // permitida na própria data, então na prática isto devolve no máximo uma linha.
    Optional<Prelecao> findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull();
}
