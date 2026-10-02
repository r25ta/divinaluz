package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.Permissao;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@code ROLE_<perfil>} (se a pessoa é staff) + um {@code PERM_<permissao>} por permissão que as
 * funções do trabalhador concedem (o que dela ela alcança). As duas pontas são deliberadamente
 * separadas, porque são telas diferentes: o <strong>perfil</strong> vem do acesso
 * ({@code /prontuario/{id}/acesso}, só o Administrador escolhe) e diz se a pessoa é staff; a
 * <strong>função</strong> vem de "Cadastrar Trabalhador" e diz quais módulos ela alcança. Logo,
 * promover alguém a trabalhador não concede acesso por si só, e o perfil ASSISTIDO não recebe
 * permissão alguma mesmo que tenha função de trabalho. O Administrador recebe todas,
 * independentemente de função.
 *
 * <p>Vive num componente próprio, e não dentro do {@code SecurityConfig}, porque há <strong>duas</strong>
 * entradas no sistema que precisam da mesma conta: o login por senha (via {@code UserDetailsService})
 * e o login por código de e-mail ({@code LoginCodigoController}), que autentica sem senha. Duplicar
 * esta regra entre as duas seria a maneira mais fácil de um perfil ganhar acesso diferente
 * dependendo de como entrou.</p>
 *
 * <p><strong>Atenção:</strong> as permissões continuam sendo resolvidas <strong>no login</strong> —
 * trocar a função de alguém só passa a valer quando ele entra de novo.</p>
 */
@Component
public class AutoridadesAssistido {

    private final TrabalhadorRepository trabalhadorRepository;

    public AutoridadesAssistido(TrabalhadorRepository trabalhadorRepository) {
        this.trabalhadorRepository = trabalhadorRepository;
    }

    /**
     * O {@link UserDetails} que vai como <em>principal</em> da autenticação. Existe aqui, e não solto
     * em cada ponto de entrada, porque os controllers recebem
     * {@code @AuthenticationPrincipal UserDetails} e chamam {@code getUsername()} — um principal que
     * não seja {@code UserDetails} chega como <strong>nulo</strong> nesse parâmetro, e o sintoma é um
     * {@code NullPointerException} no meio de uma tela que parecia não ter nada a ver com login.
     */
    public UserDetails usuario(Assistido assistido) {
        return User.withUsername(assistido.getLogin())
                .password(assistido.getSenha() == null ? SENHA_DE_FACHADA : assistido.getSenha())
                .authorities(para(assistido))
                .build();
    }

    /**
     * Ocupa o lugar da senha de quem não tem nenhuma (entra por código de e-mail), só porque
     * {@code User.password()} não aceita nulo. Não é um hash BCrypt válido, então {@code matches}
     * devolve falso para qualquer entrada — nunca serve para entrar pelo formulário.
     */
    private static final String SENHA_DE_FACHADA = "sem-senha-entra-por-codigo";

    public List<GrantedAuthority> para(Assistido assistido) {
        PerfilAcesso perfil = assistido.getPerfilAcesso();
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + perfil.name()));

        Collection<Permissao> permissoes = switch (perfil) {
            case ADMINISTRADOR -> EnumSet.allOf(Permissao.class);
            case TRABALHADOR -> Permissao.de(trabalhadorRepository.findByAssistidoId(assistido.getId())
                    .map(Trabalhador::getFuncoes)
                    .orElse(Set.of()));
            case ASSISTIDO -> Set.of();
        };
        permissoes.forEach(p -> authorities.add(new SimpleGrantedAuthority(p.getAuthority())));
        return authorities;
    }
}
