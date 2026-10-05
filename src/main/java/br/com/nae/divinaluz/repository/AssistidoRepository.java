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
    // Sem o filtro de acesso ativo: a redefinição de senha do admin (ver SenhaAdminInicial) é
    // justamente a saída para quando o acesso dele foi desativado por engano.
    Optional<Assistido> findByLogin(String login);
    boolean existsByLogin(String login);
    List<Assistido> findByLoginIsNotNullOrderByLoginAsc();
    Optional<Assistido> findByCodigoCartao(String codigoCartao);
    Optional<Assistido> findByTokenDefinicaoSenha(String tokenDefinicaoSenha);
    Optional<Assistido> findByConviteCelularHash(String conviteCelularHash);

    // Entrada por código de e-mail (ver CodigoAcessoService): ignora caixa porque o login é o e-mail
    // digitado no cadastro, e quem for pedir o código vai digitá-lo de novo, sem garantia de bater a
    // caixa — "Maria@Gmail.com" e "maria@gmail.com" são a mesma pessoa para ela.
    Optional<Assistido> findByLoginIgnoreCaseAndAcessoAtivoTrue(String login);

    // Unicidade de e-mail e login (V35): os dois sem diferenciar maiúsculas, como os índices.
    Optional<Assistido> findFirstByEmailIgnoreCase(String email);
    Optional<Assistido> findFirstByLoginIgnoreCase(String login);

    // Entrada por código: desde a V35 o login não é mais o e-mail, então a pessoa é achada pelo
    // e-mail do cadastro (único). Só quem tem acesso criado e ativo.
    Optional<Assistido> findFirstByEmailIgnoreCaseAndLoginIsNotNullAndAcessoAtivoTrue(String email);

    // Módulo de Entrevista (fila de cartões retidos — ver EntrevistaController).
    List<Assistido> findByStatusCartaoAndAtivoTrueOrderByNomeAsc(CartaoStatus statusCartao);
    long countByStatusCartaoInAndAtivoTrue(Collection<CartaoStatus> status);
}
