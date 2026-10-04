package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.service.CheckinService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * O próprio assistido marca a presença pelo celular, escaneando o QR da sessão que fica na recepção
 * (2026-10-04). Liberado a qualquer autenticado: quem marca é sempre o dono do login, nunca outra
 * pessoa — a identidade vem da autenticação, e não da URL.
 *
 * <p>O {@code GET} só confere e mostra; quem grava é o {@code POST} do botão "Confirmar minha
 * presença". Assim, abrir o link por engano (ou um pré-carregamento do navegador) não marca nada.
 * Quem não estava logado passa pelo login e volta para cá (ver SecurityConfig).</p>
 */
@Controller
public class PresencaController {

    private final CheckinService checkinService;
    private final AssistidoRepository assistidoRepository;

    public PresencaController(CheckinService checkinService, AssistidoRepository assistidoRepository) {
        this.checkinService = checkinService;
        this.assistidoRepository = assistidoRepository;
    }

    @GetMapping("/presenca/{sessaoId}/{codigo}")
    public String confirmar(@PathVariable Long sessaoId, @PathVariable String codigo,
            Authentication authentication, Model model) {
        Assistido eu = logado(authentication);
        model.addAttribute("assistido", eu);
        model.addAttribute("codigo", codigo);
        try {
            SessaoAssistencia sessao = checkinService.conferirQrDaSessao(sessaoId, codigo);
            model.addAttribute("sessao", sessao);
        } catch (RegraNegocioException e) {
            model.addAttribute("erro", e.getMessage());
        }
        return "presenca-confirmar";
    }

    @PostMapping("/presenca/{sessaoId}/{codigo}")
    public String registrar(@PathVariable Long sessaoId, @PathVariable String codigo,
            Authentication authentication, Model model) {
        Assistido eu = logado(authentication);
        model.addAttribute("assistido", eu);
        try {
            CheckinService.ResultadoAutoCheckin resultado = checkinService.registrarAutoCheckin(sessaoId, codigo, eu);
            model.addAttribute("sessao", resultado.sessao());
            model.addAttribute("resultado", resultado);
        } catch (RegraNegocioException e) {
            model.addAttribute("erro", e.getMessage());
        }
        return "presenca-confirmar";
    }

    private Assistido logado(Authentication authentication) {
        return assistidoRepository.findByLoginAndAcessoAtivoTrue(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Acesso sem cadastro vinculado."));
    }
}
