package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssistidoRepository extends JpaRepository<Assistido, Long> {
    List<Assistido> findByAtivo(boolean ativo);
    List<Assistido> findAllByOrderByNomeAsc();
    Optional<Assistido> findByLoginAndAcessoAtivoTrue(String login);
    boolean existsByLogin(String login);
    List<Assistido> findByLoginIsNotNullOrderByLoginAsc();
    Optional<Assistido> findByCodigoCartao(String codigoCartao);
    Optional<Assistido> findByTokenDefinicaoSenha(String tokenDefinicaoSenha);

    // Módulo de Entrevista (fila de cartões retidos — ver EntrevistaController).
    List<Assistido> findByStatusCartaoAndAtivoTrueOrderByNomeAsc(CartaoStatus statusCartao);
    long countByStatusCartaoInAndAtivoTrue(Collection<CartaoStatus> status);
}
