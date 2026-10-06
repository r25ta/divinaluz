package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Permissao;
import br.com.nae.divinaluz.repository.AssistidoRepository;
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
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
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
                        .requestMatchers("/login", "/entrar", "/entrar/**", "/definir-senha/**", "/acesso-negado", "/error",
                                        "/css/**", "/js/**", "/images/**", "/webjars/**",
                                        // Bootstrap/ícones servidos pelo sistema e o manifesto do app no
                                        // celular (2026-10-05): o navegador os pede sem login (o manifesto,
                                        // inclusive, sem cookies).
                                        "/vendor/**", "/icones/**", "/manifest*.json").permitAll()
                        // Próprio cartão e próprio QR: liberados a qualquer autenticado porque todo
                        // mundo alcança o seu (inclusive quem não tem permissão nenhuma). Quem pode
                        // ver o cartão DE OUTRO é checado no ProntuarioController
                        // (exigirAcessoAoCartao), que exige CONSULTA.
                        .requestMatchers("/prontuario/*/cartao", "/prontuario/*/cartao/qrcode.png").authenticated()
                        // QR da sessão escaneado pelo celular (2026-10-04): cada um marca a PRÓPRIA
                        // presença. Público desde 2026-10-05 porque a identidade pode vir do login OU do
                        // celular vinculado ao cartão (cookie) — e sem nenhum dos dois o
                        // PresencaController não marca nada. Inclui o vínculo do celular (/presenca/vincular).
                        .requestMatchers("/presenca/**").permitAll()
                        // Ver a escala é de todos (inclusive do perfil ASSISTIDO); montá-la, não.
                        .requestMatchers(HttpMethod.GET, "/prelecao").authenticated()
                        .requestMatchers("/prelecao/**").hasAuthority(Permissao.PRELECAO.getAuthority())
                        .requestMatchers("/trabalhadores/**", "/usuarios", "/prontuario/*/trabalhador")
                                .hasAuthority(Permissao.TRABALHADORES.getAuthority())
                        .requestMatchers("/sessao", "/sessao/**", "/checkin/**",
                                        "/prontuario/*/nova-sessao", "/prontuario/*/sessao")
                                .hasAuthority(Permissao.SESSAO.getAuthority())
                        // Avaliação e Entrevista separadas desde 2026-10-04 (Avaliador x Entrevistador); a
                        // fila de cartões retidos é de quem faz qualquer uma das duas.
                        .requestMatchers("/prontuario/*/nova-avaliacao", "/prontuario/*/avaliacao")
                                .hasAuthority(Permissao.AVALIACAO.getAuthority())
                        .requestMatchers("/prontuario/*/nova-entrevista", "/prontuario/*/entrevista")
                                .hasAuthority(Permissao.ENTREVISTA.getAuthority())
                        .requestMatchers("/entrevistas")
                                .hasAnyAuthority(Permissao.AVALIACAO.getAuthority(), Permissao.ENTREVISTA.getAuthority())
                        // Prontuário x cadastro (2026-10-04): trocar tratamento e dia de assistência pelo
                        // prontuário não é dado cadastral — a Recepcionista cadastra, mas não altera isso.
                        .requestMatchers("/prontuario/*/dia-frequencia", "/prontuario/*/tratamento")
                                .hasAuthority(Permissao.PRONTUARIO.getAuthority())
                        // Preletor convidado (V41): cadastrado no "Novo Cadastro", então é CADASTRO.
                        .requestMatchers("/novo", "/salvar", "/convidados", "/convidados/**", "/prontuario/*/editar",
                                        "/prontuario/*/desativar", "/prontuario/*/reativar",
                                        "/prontuario/*/acesso", "/prontuario/*/reenviar-codigo",
                                        "/prontuario/*/celular/**",
                                        "/acesso/verificar")
                                .hasAuthority(Permissao.CADASTRO.getAuthority())
                        // "/" é o endereço que se abre ao chegar (inclusive já lembrado pelo remember-me):
                        // liberado a qualquer autenticado, e o ProntuarioController.index manda para o
                        // próprio cartão quem não tem CONSULTA, em vez de um 403 na porta de entrada.
                        .requestMatchers("/").authenticated()
                        .requestMatchers("/prontuario/*").hasAuthority(Permissao.CONSULTA.getAuthority())
                        // Rede de segurança para qualquer rota nova ainda não classificada: continua
                        // exigindo staff, como era antes das permissões.
                        .anyRequest().hasAnyRole("ADMINISTRADOR", "TRABALHADOR"))
                // 403 com explicação em vez da "Whitelabel Error Page" (ver AcessoNegadoController). O
                // caso mais comum não é falta de permissão: é um formulário aberto antes de um novo
                // login no mesmo navegador, cujo token CSRF ficou da sessão anterior.
                .exceptionHandling(excecoes -> excecoes.accessDeniedHandler(acessoNegado()))
                .formLogin(form -> form
                        .loginPage("/login")
                    // Todo mundo — assistido ou trabalhador — entra pelo próprio cartão de tratamento
                    // (pedido de 2026-10-04). Antes, quem não era ASSISTIDO ia para a listagem "/", que
                    // exige CONSULTA: um Expositor/Preletor entrava direto num 403.
                    .successHandler((request, response, authentication) -> {
                        Assistido logado = assistidoRepository.findByLoginAndAcessoAtivoTrue(authentication.getName())
                            .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
                        response.sendRedirect(DestinoAposLogin.url(request, response, logado));
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
     *
     * <p><strong>A chave é aleatória por execução, e isso é suficiente</strong> (verificado em
     * 2026-10-02 reiniciando o contêiner): nesta variante o cookie carrega série/token conferidos
     * contra a tabela, e não uma assinatura derivada da chave, então reiniciar não derruba os
     * aparelhos lembrados. Importa porque o plano gratuito do Render hiberna a cada 15 minutos — se
     * dependesse da chave, "lembrar deste aparelho" não serviria para nada ali. Por isso também não
     * existe variável de ambiente para configurá-la: seria um botão que não muda nada.</p>
     *
     * <p>Dois comportamentos desta variante que confundem quem for depurar: o <strong>token é
     * rotacionado a cada uso</strong> (um cookie capturado serve uma vez só; reapresentá-lo é lido
     * como roubo de token e <em>apaga todos</em> os registros daquela pessoa), e uma requisição
     * autenticada <strong>apenas</strong> por remember-me que tropece numa
     * {@code AccessDeniedException} é mandada para o login (302) em vez de receber 403, porque o
     * Spring não a considera plenamente autenticada.</p>
     */
    @Bean
    RememberMeServices rememberMeServices(DataSource dataSource, AssistidoRepository assistidoRepository,
            AutoridadesAssistido autoridades) {
        JdbcTokenRepositoryImpl repositorio = new JdbcTokenRepositoryImpl();
        repositorio.setDataSource(dataSource);

        UserDetailsService semExigirSenha = login -> assistidoRepository
                .findByLoginAndAcessoAtivoTrue(login)
                .filter(a -> a.getPerfilAcesso() != null)
                .map(autoridades::usuario)
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));

        PersistentTokenBasedRememberMeServices servicos = new PersistentTokenBasedRememberMeServices(
                UUID.randomUUID().toString(), semExigirSenha, repositorio);
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
        // Sem diferenciar maiúsculas (V35): quem digita "Maria.Souza" no celular, com a primeira letra
        // maiúscula automática do teclado, entra do mesmo jeito. O principal leva o login como está
        // gravado, que é o que as demais buscas por login usam depois.
        return login -> {
            String digitado = login == null ? "" : login.trim();
            Assistido assistido = assistidoRepository.findByLoginAndAcessoAtivoTrue(digitado)
                    .or(() -> assistidoRepository.findByLoginIgnoreCaseAndAcessoAtivoTrue(digitado))
                    .filter(a -> a.getSenha() != null && a.getPerfilAcesso() != null)
                    .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
            return User.withUsername(assistido.getLogin())
                    .password(assistido.getSenha())
                    .authorities(autoridades.para(assistido))
                    .build();
        };
    }

    // Encaminha (forward, não redirect) para a página do 403: o pedido negado continua sendo o mesmo,
    // então a página sabe o motivo pelo atributo WebAttributes.ACCESS_DENIED_403.
    private static AccessDeniedHandler acessoNegado() {
        AccessDeniedHandlerImpl handler = new AccessDeniedHandlerImpl();
        handler.setErrorPage("/acesso-negado");
        return handler;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}