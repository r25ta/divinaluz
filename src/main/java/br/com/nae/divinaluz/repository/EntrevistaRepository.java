package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Entrevista;
import br.com.nae.divinaluz.model.TipoEntrevista;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface EntrevistaRepository extends JpaRepository<Entrevista, Long> {
    List<Entrevista> findByAssistidoIdOrderByDataDesc(Long assistidoId);
    List<Entrevista> findByAssistidoIdAndTipoOrderByDataDesc(Long assistidoId, TipoEntrevista tipo);
    List<Entrevista> findByAssistidoIdAndTipoNotOrderByDataDescIdDesc(Long assistidoId, TipoEntrevista tipo);
    boolean existsByAvaliacaoId(Long avaliacaoId);

    // Desde a V43 só a entrevista de TRATAMENTO libera o cartão (regra das 4 sessões): a da 1ª sessão
    // e a excepcional não entram nessas contagens.
    long countByAssistidoIdAndTipo(Long assistidoId, TipoEntrevista tipo);
    long countByAssistidoIdAndTipoAndDataGreaterThanEqual(Long assistidoId, TipoEntrevista tipo, LocalDate desde);

    // Atendimentos que dependem das presenças (desfazer presença recusa se houver): todos menos a
    // excepcional, que não tem ligação com o cartão.
    long countByAssistidoIdAndTipoNotAndDataGreaterThanEqual(Long assistidoId, TipoEntrevista tipo, LocalDate desde);

    long countByDataAndTipo(LocalDate data, TipoEntrevista tipo);
}
