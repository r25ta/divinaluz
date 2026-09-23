package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.Usuario;
import br.com.nae.divinaluz.repository.UsuarioRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, UsuarioRepository usuarioRepository) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/cadastro", "/cadastro/**", "/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                        .requestMatchers("/usuarios/**").hasRole("ADMINISTRADOR")
                        .requestMatchers("/prontuario/*/cartao").authenticated()
                        .requestMatchers("/**").hasAnyRole("ADMINISTRADOR", "TRABALHADOR")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                    .successHandler((request, response, authentication) -> {
                        boolean assistido = authentication.getAuthorities().stream()
                            .anyMatch(authority -> authority.getAuthority().equals("ROLE_ASSISTIDO"));
                        if (assistido) {
                        Usuario usuario = usuarioRepository.findByLoginAndAtivoTrue(authentication.getName())
                            .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
                        response.sendRedirect(request.getContextPath() + "/prontuario/"
                            + usuario.getAssistido().getId() + "/cartao");
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
    UserDetailsService userDetailsService(UsuarioRepository usuarioRepository) {
        return login -> {
            Usuario usuario = usuarioRepository.findByLoginAndAtivoTrue(login)
                    .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
            return User.withUsername(usuario.getLogin())
                    .password(usuario.getSenha())
                    .roles(usuario.getPerfil().name())
                    .build();
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}