package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.Assistido;
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

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AssistidoRepository assistidoRepository) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                        .requestMatchers("/usuarios/**").hasRole("ADMINISTRADOR")
                        .requestMatchers("/prontuario/*/cartao").authenticated()
                        // O QR do próprio cartão precisa carregar para o assistido; a posse é
                        // checada no ProntuarioController (exigirAcessoAoCartao).
                        .requestMatchers("/prontuario/*/cartao/qrcode.png").authenticated()
                        .requestMatchers(HttpMethod.GET, "/prelecao").authenticated()
                        .requestMatchers("/**").hasAnyRole("ADMINISTRADOR", "TRABALHADOR")
                        .anyRequest().authenticated())
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
    UserDetailsService userDetailsService(AssistidoRepository assistidoRepository) {
        return login -> {
            Assistido assistido = assistidoRepository.findByLoginAndAcessoAtivoTrue(login)
                    .filter(a -> a.getSenha() != null && a.getPerfilAcesso() != null)
                    .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
            return User.withUsername(assistido.getLogin())
                    .password(assistido.getSenha())
                    .roles(assistido.getPerfilAcesso().name())
                    .build();
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}