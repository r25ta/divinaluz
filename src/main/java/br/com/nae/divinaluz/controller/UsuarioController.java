package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.ProvedorIdentidade;
import br.com.nae.divinaluz.model.Usuario;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class UsuarioController {

    private final UsuarioRepository usuarioRepository;
    private final AssistidoRepository assistidoRepository;
    private final PasswordEncoder passwordEncoder;

    public UsuarioController(UsuarioRepository usuarioRepository, AssistidoRepository assistidoRepository,
            PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.assistidoRepository = assistidoRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/usuarios")
    public String listar(Model model) {
        model.addAttribute("usuarios", usuarioRepository.findAllByOrderByLoginAsc());
        return "usuario-lista";
    }

    @GetMapping("/usuarios/novo")
    public String novo(Model model) {
        prepararFormulario(model, new Usuario());
        return "usuario-form";
    }

    @GetMapping("/usuarios/{id}/editar")
    public String editar(@PathVariable Long id, Model model) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Usuário inválido: " + id));
        prepararFormulario(model, usuario);
        return "usuario-form";
    }

    @PostMapping("/usuarios/salvar")
    public String salvar(@ModelAttribute Usuario dados, @RequestParam(required = false) Long assistidoId,
            @RequestParam(required = false) String senha, RedirectAttributes redirectAttributes) {
        Usuario usuario = dados.getId() == null ? new Usuario() : usuarioRepository.findById(dados.getId())
                .orElseThrow(() -> new IllegalArgumentException("Usuário inválido: " + dados.getId()));

        if (dados.getId() == null && usuarioRepository.existsByLogin(dados.getLogin())) {
            redirectAttributes.addFlashAttribute("erro", "Já existe um usuário com este login.");
            return "redirect:/usuarios/novo";
        }
        if (dados.getId() != null && !usuario.getLogin().equals(dados.getLogin())
                && usuarioRepository.existsByLogin(dados.getLogin())) {
            redirectAttributes.addFlashAttribute("erro", "Já existe um usuário com este login.");
            return "redirect:/usuarios/" + dados.getId() + "/editar";
        }
        if (dados.getPerfil() == null) {
            redirectAttributes.addFlashAttribute("erro", "Selecione um perfil de acesso.");
            return redirecionarFormulario(dados);
        }
        if (dados.getPerfil() == PerfilAcesso.ASSISTIDO && assistidoId == null) {
            redirectAttributes.addFlashAttribute("erro", "Selecione o assistido vinculado ao usuário.");
            return redirecionarFormulario(dados);
        }
        if (dados.getId() == null && (senha == null || senha.isBlank())) {
            redirectAttributes.addFlashAttribute("erro", "Informe uma senha para o novo usuário.");
            return "redirect:/usuarios/novo";
        }
        if (dados.getId() == null && senha.length() < 6) {
            redirectAttributes.addFlashAttribute("erro", "A senha deve ter pelo menos 6 caracteres.");
            return "redirect:/usuarios/novo";
        }

        usuario.setLogin(dados.getLogin().trim());
        usuario.setEmail(dados.getEmail() == null || dados.getEmail().isBlank() ? null : dados.getEmail().trim());
        usuario.setPerfil(dados.getPerfil());
        usuario.setProvedor(dados.getProvedor() == null ? ProvedorIdentidade.LOCAL : dados.getProvedor());
        usuario.setIdentificadorExterno(dados.getIdentificadorExterno() == null
            || dados.getIdentificadorExterno().isBlank() ? null : dados.getIdentificadorExterno().trim());
        if (dados.getPerfil() == PerfilAcesso.ASSISTIDO) {
            usuario.setAssistido(assistidoRepository.findById(assistidoId)
                    .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId)));
        } else {
            usuario.setAssistido(null);
        }
        if (dados.getId() == null || (senha != null && !senha.isBlank())) {
            usuario.setSenha(passwordEncoder.encode(senha));
        }
        usuarioRepository.save(usuario);
        redirectAttributes.addFlashAttribute("sucesso", "Usuário salvo com sucesso.");
        return "redirect:/usuarios";
    }

    @PostMapping("/usuarios/{id}/senha")
    public String alterarSenha(@PathVariable Long id, @RequestParam String senha,
            RedirectAttributes redirectAttributes) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Usuário inválido: " + id));
        if (senha == null || senha.length() < 6) {
            redirectAttributes.addFlashAttribute("erro", "A senha deve ter pelo menos 6 caracteres.");
        } else {
            usuario.setSenha(passwordEncoder.encode(senha));
            usuarioRepository.save(usuario);
            redirectAttributes.addFlashAttribute("sucesso", "Senha alterada com sucesso.");
        }
        return "redirect:/usuarios";
    }

    @PostMapping("/usuarios/{id}/alternar")
    public String alternarAtivo(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Usuário inválido: " + id));
        usuario.setAtivo(!usuario.isAtivo());
        usuarioRepository.save(usuario);
        redirectAttributes.addFlashAttribute("sucesso", "Status do usuário atualizado.");
        return "redirect:/usuarios";
    }

    private void prepararFormulario(Model model, Usuario usuario) {
        model.addAttribute("usuario", usuario);
        model.addAttribute("perfis", PerfilAcesso.values());
        model.addAttribute("provedores", ProvedorIdentidade.values());
        model.addAttribute("assistidos", assistidoRepository.findAllByOrderByNomeAsc());
    }

    private String redirecionarFormulario(Usuario usuario) {
        return usuario.getId() == null ? "redirect:/usuarios/novo" : "redirect:/usuarios/" + usuario.getId() + "/editar";
    }

}