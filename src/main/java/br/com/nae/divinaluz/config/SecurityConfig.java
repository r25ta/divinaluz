package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.Permissao;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AssistidoRepository assistidoRepository) throws Exception {
        http
                // Visibilidade por perfil de trabalho: cada rota exige a Permissao do módulo a que
                // pertence, e as permissões vêm das funções do trabalhador (ver TipoTrabalhador).
                // O Administrador recebe todas, então não precisa aparecer regra a regra.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/definir-senha/**", "/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                        // Próprio cartão e próprio QR: liberados a qualquer autenticado porque todo
                        // mundo alcança o seu (inclusive quem não tem permissão nenhuma). Quem pode
                        // ver o cartão DE OUTRO é checado no ProntuarioController
                        // (exigirAcessoAoCartao), que exige CONSULTA.
                        .requestMatchers("/prontuario/*/cartao", "/prontuario/*/cartao/qrcode.png").authenticated()
                        // Ver a escala é de todos (inclusive do perfil ASSISTIDO); montá-la, não.
                        .requestMatchers(HttpMethod.GET, "/prelecao").authenticated()
                        .requestMatchers("/prelecao/**").hasAuthority(Permissao.PRELECAO.getAuthority())
                        .requestMatchers("/trabalhadores/**", "/usuarios", "/prontuario/*/trabalhador")
                                .hasAuthority(Permissao.TRABALHADORES.getAuthority())
                        .requestMatchers("/sessao", "/sessao/**", "/checkin/**",
                                        "/prontuario/*/nova-sessao", "/prontuario/*/sessao")
                                .hasAuthority(Permissao.SESSAO.getAuthority())
                        .requestMatchers("/entrevistas",
                                        "/prontuario/*/nova-avaliacao", "/prontuario/*/avaliacao",
                                        "/prontuario/*/nova-entrevista", "/prontuario/*/entrevista")
                                .hasAuthority(Permissao.ENTREVISTA.getAuthority())
                        .requestMatchers("/novo", "/salvar", "/prontuario/*/editar",
                                        "/prontuario/*/desativar", "/prontuario/*/reativar",
                                        "/prontuario/*/dia-frequencia", "/prontuario/*/tratamento",
                                        "/prontuario/*/acesso")
                                .hasAuthority(Permissao.CADASTRO.getAuthority())
                        .requestMatchers("/", "/prontuario/*").hasAuthority(Permissao.CONSULTA.getAuthority())
                        // Rede de segurança para qualquer rota nova ainda não classificada: continua
                        // exigindo staff, como era antes das permissões.
                        .anyRequest().hasAnyRole("ADMINISTRADOR", "TRABALHADOR"))
                .formLogin(form -> form
                        .loginPage("/login")
                    .successHandler((request, response, authentication) -> {
                        boolean assistido = authentication.getAuthorities().stream()
                            .anyMatch(authority -> authority.getAuthority().equals("ROLE_ASSISTIDO"));
                        if (assistido) {
                        Assistido logado = assistidoRepository.findByLoginAndAcessoAtivoTrue(authentication.getName())
                            .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
                        response.sendRedirect(request.getContextPath() + "/prontuario/"
                            + logado.getId() + "/cartao");
                        } else {
                        response.sendRedirect(request.getContextPath() + "/");
                        }
                    })
                        .failureUrl("/login?erro=true")
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout=true")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll());
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(AssistidoRepository assistidoRepository,
            TrabalhadorRepository trabalhadorRepository) {
        return login -> {
            Assistido assistido = assistidoRepository.findByLoginAndAcessoAtivoTrue(login)
                    .filter(a -> a.getSenha() != null && a.getPerfilAcesso() != null)
                    .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
            return User.withUsername(assistido.getLogin())
                    .password(assistido.getSenha())
                    .authorities(authorities(assistido, trabalhadorRepository))
                    .build();
        };
    }

    /**
     * {@code ROLE_<perfil>} (se a pessoa é staff) + um {@code PERM_<permissao>} por permissão que as
     * funções do trabalhador concedem (o que dela ela alcança). As duas pontas são deliberadamente
     * separadas, porque são telas diferentes: o <strong>perfil</strong> vem do acesso
     * ({@code /prontuario/{id}/acesso}, só o Administrador escolhe) e diz se a pessoa é staff; a
     * <strong>função</strong> vem de "Cadastrar Trabalhador" e diz quais módulos ela alcança. Logo,
     * promover alguém a trabalhador não concede acesso por si só, e o perfil ASSISTIDO não recebe
     * permissão alguma mesmo que tenha função de trabalho. O Administrador recebe todas,
     * independentemente de função.
     */
    private List<GrantedAuthority> authorities(Assistido assistido,
            TrabalhadorRepository trabalhadorRepository) {
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

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}