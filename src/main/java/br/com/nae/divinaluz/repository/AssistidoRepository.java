package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssistidoRepository extends JpaRepository<Assistido, Long> {

    /**
     * As pessoas atendidas pela casa — todo cadastro MENOS o preletor convidado (V41), que fica fora
     * da listagem de prontuários, da busca da recepção e da promoção a trabalhador. {@code IS NULL}
     * porque cadastros antigos podem não ter vínculo, e {@code <>} sozinho os descartaria.
     */
    @Query("select a from Assistido a where a.ativo = :ativo and (a.vinculo is null or a.vinculo <> 'CONVIDADO')")
    List<Assistido> atendidosPorAtivo(@Param("ativo") boolean ativo);

    @Query("select a from Assistido a where a.vinculo is null or a.vinculo <> 'CONVIDADO'")
    List<Assistido> atendidos();
    /** Quantos cadastros desativados existem — o link "Mostrar inativos" da listagem diz quantos são. */
    @Query("select count(a) from Assistido a where a.ativo = false and (a.vinculo is null or a.vinculo <> 'CONVIDADO')")
    long contarInativos();

    List<Assistido> findByVinculoOrderByNomeAsc(String vinculo);

    List<Assistido> findByVinculoAndAtivoTrueOrderByNomeAsc(String vinculo);
    Optional<Assistido> findByLoginAndAcessoAtivoTrue(String login);
    // Sem o filtro de acesso ativo: a redefinição de senha do admin (ver SenhaAdminInicial) é
    // justamente a saída para quando o acesso dele foi desativado por engano.
    Optional<Assistido> findByLogin(String login);
    Optional<Assistido> findByCodigoCartao(String codigoCartao);
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

    // Número de pendências no item Prontuário do menu (PerfilAdvice.filaEntrevistas). A fila em si é a
    // listagem de Prontuário desde 2026-10-11.
    long countByStatusCartaoInAndAtivoTrue(Collection<CartaoStatus> status);

    /**
     * Trava a linha do cadastro até o fim da transação (2026-10-13): quem marca presença para a mesma
     * pessoa ao mesmo tempo — o toque duplo no celular, a recepção carimbando enquanto ela escaneia o QR
     * da sessão — espera a vez, e a regra "uma presença por semana" passa a enxergar a que acabou de ser
     * gravada. Sem a trava, as duas liam "nenhuma presença nesta semana" e o cartão ganhava duas
     * presenças efetivas no mesmo dia.
     *
     * <p>{@code NO KEY UPDATE} e não {@code UPDATE}: a presença nova aponta para este cadastro (FK), e o
     * PostgreSQL deixa gravá-la enquanto a trava existe. Não relê nada para a memória: as mudanças ainda
     * não gravadas no cadastro (ex.: o dia de assistência trocado logo antes) continuam valendo.</p>
     */
    @Transactional
    @Query(value = "SELECT id FROM assistido WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Long travarParaAtualizacao(@Param("id") Long id);
}
