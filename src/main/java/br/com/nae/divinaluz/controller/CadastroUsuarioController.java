package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.ProvedorIdentidade;
import br.com.nae.divinaluz.model.Usuario;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class CadastroUsuarioController {

    private final UsuarioRepository usuarioRepository;
    private final AssistidoRepository assistidoRepository;
    private final PasswordEncoder passwordEncoder;

    public CadastroUsuarioController(UsuarioRepository usuarioRepository, AssistidoRepository assistidoRepository,
            PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.assistidoRepository = assistidoRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/cadastro")
    public String cadastro() {
        return "cadastro-usuario";
    }

    @PostMapping("/cadastro")
    public String salvar(@RequestParam String nome, @RequestParam String login, @RequestParam String email,
            @RequestParam String senha, @RequestParam String confirmacaoSenha,
            RedirectAttributes redirectAttributes, Model model) {
        if (nome == null || nome.isBlank() || login == null || login.isBlank() || email == null || email.isBlank()
                || senha == null || senha.length() < 6) {
            model.addAttribute("erro", "Preencha nome, login, e-mail e uma senha com pelo menos 6 caracteres.");
            return "cadastro-usuario";
        }
        if (!senha.equals(confirmacaoSenha)) {
            model.addAttribute("erro", "A confirmação da senha não confere.");
            return "cadastro-usuario";
        }
        if (usuarioRepository.existsByLogin(login.trim())) {
            model.addAttribute("erro", "Já existe um usuário com este login.");
            return "cadastro-usuario";
        }

        Assistido assistido = new Assistido();
        assistido.setNome(nome.trim());
        assistido.setVinculo("ASSISTIDO");
        assistido.setAtivo(true);
        assistido = assistidoRepository.save(assistido);

        Usuario usuario = new Usuario();
        usuario.setLogin(login.trim());
        usuario.setEmail(email.trim());
        usuario.setSenha(passwordEncoder.encode(senha));
        usuario.setPerfil(PerfilAcesso.ASSISTIDO);
        usuario.setProvedor(ProvedorIdentidade.LOCAL);
        usuario.setAssistido(assistido);
        usuario.setAtivo(true);
        usuarioRepository.save(usuario);

        redirectAttributes.addFlashAttribute("sucesso",
            "Cadastro realizado com sucesso. Faça login para consultar seu cartão.");
        return "redirect:/login";
    }
}