package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Tela pública (sem login) que o assistido abre a partir do link de e-mail enviado no cadastro
 * (ver AcessoService) para definir a própria senha de acesso.
 */
@Controller
public class DefinirSenhaController {

    private final AssistidoRepository assistidoRepository;
    private final PasswordEncoder passwordEncoder;

    public DefinirSenhaController(AssistidoRepository assistidoRepository, PasswordEncoder passwordEncoder) {
        this.assistidoRepository = assistidoRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/definir-senha/{token}")
    public String formulario(@PathVariable String token, Model model) {
        Optional<Assistido> assistido = tokenValido(token);
        if (assistido.isEmpty()) {
            model.addAttribute("expirado", true);
            return "definir-senha";
        }
        model.addAttribute("token", token);
        model.addAttribute("nome", assistido.get().getNome());
        return "definir-senha";
    }

    @PostMapping("/definir-senha/{token}")
    public String salvar(@PathVariable String token, @RequestParam String senha,
            @RequestParam String confirmacaoSenha, RedirectAttributes redirectAttributes, Model model) {
        Optional<Assistido> assistidoOpt = tokenValido(token);
        if (assistidoOpt.isEmpty()) {
            model.addAttribute("expirado", true);
            return "definir-senha";
        }
        Assistido assistido = assistidoOpt.get();

        if (senha == null || senha.length() < 6) {
            model.addAttribute("erro", "Informe uma senha com pelo menos 6 caracteres.");
            model.addAttribute("token", token);
            model.addAttribute("nome", assistido.getNome());
            return "definir-senha";
        }
        if (!senha.equals(confirmacaoSenha)) {
            model.addAttribute("erro", "A confirmação da senha não confere.");
            model.addAttribute("token", token);
            model.addAttribute("nome", assistido.getNome());
            return "definir-senha";
        }

        assistido.setSenha(passwordEncoder.encode(senha));
        assistido.setTokenDefinicaoSenha(null);
        assistido.setTokenDefinicaoSenhaExpiraEm(null);
        assistido.setAcessoAtivo(true);
        assistidoRepository.save(assistido);

        redirectAttributes.addFlashAttribute("sucesso", "Senha definida com sucesso! Faça login para acessar seu cartão.");
        return "redirect:/login";
    }

    private Optional<Assistido> tokenValido(String token) {
        return assistidoRepository.findByTokenDefinicaoSenha(token)
                .filter(a -> a.getTokenDefinicaoSenhaExpiraEm() != null
                        && a.getTokenDefinicaoSenhaExpiraEm().isAfter(LocalDateTime.now()));
    }
}
