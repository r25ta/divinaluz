package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Permissao;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;

import javax.sql.DataSource;
import java.util.UUID;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Noventa dias de "lembrar deste aparelho" — ver {@link #rememberMeServices}. */
    private static final int VALIDADE_LEMBRAR_SEGUNDOS = 90 * 24 * 60 * 60;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AssistidoRepository assistidoRepository,
            RememberMeServices rememberMeServices) throws Exception {
        http
                // Visibilidade por perfil de trabalho: cada rota exige a Permissao do módulo a que
                // pertence, e as permissões vêm das funções do trabalhador (ver TipoTrabalhador).
                // O Administrador recebe todas, então não precisa aparecer regra a regra.
                .authorizeHttpRequests(auth -> auth
                        // "/entrar/**" é a entrada por código de e-mail (ver CodigoAcessoService): é
                        // pública por natureza, como o /login — quem a usa ainda não está autenticado.
                        .requestMatchers("/login", "/entrar", "/entrar/**", "/definir-senha/**",
                                        "/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
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
                                        "/prontuario/*/acesso", "/prontuario/*/reenviar-codigo")
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
                // Sem isto, entrar por código exigiria uma ida ao e-mail toda semana — mais atrito que
                // a senha, não menos. O LoginCodigoController chama loginSuccess por conta própria,
                // porque ali o login não passa pelo filtro do formulário.
                .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout=true")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID", "remember-me")
                        .permitAll());
        return http.build();
    }

    /**
     * Variante <strong>persistente</strong> (série/token numa tabela) em vez da baseada em hash, por
     * uma razão que não é preferência: a baseada em hash assina o cookie com a senha do usuário, e
     * quem entra por código de e-mail não tem senha nenhuma ({@code senha == null}).
     *
     * <p>Usa um {@code UserDetailsService} próprio, que de propósito <strong>não</strong> exige
     * {@code senha != null} — ao contrário do bean usado pelo login por formulário. A senha de fachada
     * jamais confere: este serviço só é consultado pelo remember-me, que não compara senha alguma.</p>
     */
    @Bean
    RememberMeServices rememberMeServices(DataSource dataSource, AssistidoRepository assistidoRepository,
            AutoridadesAssistido autoridades, @Value("${app.remember-me.chave:}") String chaveConfigurada) {
        JdbcTokenRepositoryImpl repositorio = new JdbcTokenRepositoryImpl();
        repositorio.setDataSource(dataSource);

        UserDetailsService semExigirSenha = login -> assistidoRepository
                .findByLoginAndAcessoAtivoTrue(login)
                .filter(a -> a.getPerfilAcesso() != null)
                .map(autoridades::usuario)
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));

        // Sem chave configurada, uma por execução: os cookies caem num restart, o que é o
        // comportamento seguro por omissão. Em produção, defina APP_REMEMBER_ME_CHAVE para os
        // aparelhos continuarem lembrados entre deploys (ver application-prod.properties).
        String chave = chaveConfigurada == null || chaveConfigurada.isBlank()
                ? UUID.randomUUID().toString()
                : chaveConfigurada;

        PersistentTokenBasedRememberMeServices servicos =
                new PersistentTokenBasedRememberMeServices(chave, semExigirSenha, repositorio);
        servicos.setTokenValiditySeconds(VALIDADE_LEMBRAR_SEGUNDOS);
        servicos.setParameter(PARAMETRO_LEMBRAR);
        return servicos;
    }

    /** Nome do checkbox "lembrar deste aparelho", lido pelo remember-me a partir do request. */
    public static final String PARAMETRO_LEMBRAR = "remember-me";

    /**
     * Login por <strong>senha</strong>. Continua exigindo {@code senha != null}, e isso agora tem um
     * segundo motivo além do original (acesso recém-criado com senha pendente): quem entra por código
     * de e-mail não tem senha, e não deve conseguir entrar pelo formulário de jeito nenhum.
     */
    @Bean
    UserDetailsService userDetailsService(AssistidoRepository assistidoRepository,
            AutoridadesAssistido autoridades) {
        return login -> {
            Assistido assistido = assistidoRepository.findByLoginAndAcessoAtivoTrue(login)
                    .filter(a -> a.getSenha() != null && a.getPerfilAcesso() != null)
                    .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
            return User.withUsername(assistido.getLogin())
                    .password(assistido.getSenha())
                    .authorities(autoridades.para(assistido))
                    .build();
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}