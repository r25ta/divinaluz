package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// Os "usuários" são os assistidos que têm login (Assistido.login != null). Esta tela só lista e
// ativa/desativa acessos; criar ou editar (login, perfil, senha) é feito em /prontuario/{id}/acesso.
@Controller
public class UsuarioController {

    private final AssistidoRepository assistidoRepository;

    public UsuarioController(AssistidoRepository assistidoRepository) {
        this.assistidoRepository = assistidoRepository;
    }

    @GetMapping("/usuarios")
    public String listar(Model model) {
        model.addAttribute("usuarios", assistidoRepository.findByLoginIsNotNullOrderByLoginAsc());
        return "usuario-lista";
    }

    @PostMapping("/usuarios/{assistidoId}/alternar")
    public String alternarAcesso(@PathVariable Long assistidoId, Authentication authentication,
            RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
        if (assistido.getLogin() != null && assistido.getLogin().equals(authentication.getName())) {
            redirectAttributes.addFlashAttribute("erro", "Você não pode desativar o próprio acesso.");
            return "redirect:/usuarios";
        }
        assistido.setAcessoAtivo(!assistido.isAcessoAtivo());
        assistidoRepository.save(assistido);
        redirectAttributes.addFlashAttribute("sucesso", "Status do acesso atualizado.");
        return "redirect:/usuarios";
    }
}
