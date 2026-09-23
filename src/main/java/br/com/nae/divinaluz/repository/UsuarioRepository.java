package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByLoginAndAtivoTrue(String login);
    boolean existsByLogin(String login);
    java.util.List<Usuario> findAllByOrderByLoginAsc();
}