package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.config.CookieCelular;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.service.CelularVinculadoService;
import br.com.nae.divinaluz.service.CelularVinculadoService.PessoaDoCelular;
import br.com.nae.divinaluz.service.CheckinService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * O próprio assistido marca a presença pelo celular, escaneando o QR da sessão que fica na recepção
 * (2026-10-04). Quem marca é sempre o dono da identidade, nunca alguém escolhido pela URL.
 *
 * <p><strong>Sem login desde 2026-10-05:</strong> a identidade vem do login <em>ou</em> do celular
 * vinculado ao cartão (cookie, ver CelularVinculadoService) — e o celular pode estar vinculado a
 * várias pessoas da mesma família, cada uma com o seu botão. Celular sem vínculo e sem login vê as
 * duas saídas: entrar (e voltar para cá) ou pedir à recepção o QR de vínculo.</p>
 *
 * <p>O {@code GET} só confere e mostra; quem grava é o {@code POST} do botão "Confirmar presença".
 * Assim, abrir o link por engano (ou um pré-carregamento do navegador) não marca nada.</p>
 */
@Controller
public class PresencaController {

    private final CheckinService checkinService;
    private final AssistidoRepository assistidoRepository;
    private final CelularVinculadoService celularVinculadoService;

    public PresencaController(CheckinService checkinService, AssistidoRepository assistidoRepository,
            CelularVinculadoService celularVinculadoService) {
        this.checkinService = checkinService;
        this.assistidoRepository = assistidoRepository;
        this.celularVinculadoService = celularVinculadoService;
    }

    @GetMapping("/presenca/{sessaoId}/{codigo}")
    public String confirmar(@PathVariable Long sessaoId, @PathVariable String codigo,
            Authentication authentication, HttpServletRequest request, HttpServletResponse response, Model model) {
        Quem quem = identificar(authentication, request, response);
        if (quem.ninguem()) {
            // Para o "Entrar" desta tela voltar aqui depois do login (ver DestinoAposLogin).
            new HttpSessionRequestCache().saveRequest(request, response);
        }
        preparar(model, sessaoId, codigo, quem, null);
        try {
            model.addAttribute("sessao", checkinService.conferirQrDaSessao(sessaoId, codigo));
        } catch (RegraNegocioException e) {
            model.addAttribute("erro", e.getMessage());
        }
        return "presenca-confirmar";
    }

    @PostMapping("/presenca/{sessaoId}/{codigo}")
    public String registrar(@PathVariable Long sessaoId, @PathVariable String codigo,
            @RequestParam(required = false) Long assistidoId,
            @RequestParam(defaultValue = "false") boolean lembrar,
            Authentication authentication, HttpServletRequest request, HttpServletResponse response, Model model) {
        Quem identificado = identificar(authentication, request, response);
        // Só se marca quem este celular (ou este login) identifica — o id do formulário apenas escolhe
        // entre eles, nunca acrescenta alguém.
        List<Assistido> todos = identificado.todos();
        Optional<Assistido> marcado = todos.stream()
                .filter(a -> assistidoId == null ? todos.size() == 1 : a.getId().equals(assistidoId))
                .findFirst();
        if (marcado.isEmpty()) {
            return "redirect:/presenca/" + sessaoId + "/" + codigo;
        }

        // "Lembrar este celular", oferecido a quem marcou logado: na próxima vez, sem login.
        Quem quem = identificado;
        if (lembrar && quem.logado() != null && !quem.vinculadoAoCelular(quem.logado())) {
            try {
                List<String> tokens = celularVinculadoService.vincular(quem.doCelular(), quem.logado());
                CookieCelular.gravar(request, response, tokens);
                quem = new Quem(quem.logado(), celularVinculadoService.identificar(tokens));
                model.addAttribute("acabouDeVincular", true);
            } catch (RegraNegocioException e) {
                model.addAttribute("avisoVinculo", e.getMessage());
            }
        }

        preparar(model, sessaoId, codigo, quem, marcado.get());
        try {
            CheckinService.ResultadoAutoCheckin resultado =
                    checkinService.registrarAutoCheckin(sessaoId, codigo, marcado.get());
            model.addAttribute("sessao", resultado.sessao());
            model.addAttribute("resultado", resultado);
        } catch (RegraNegocioException e) {
            model.addAttribute("erro", e.getMessage());
            try {
                model.addAttribute("sessao", checkinService.conferirQrDaSessao(sessaoId, codigo));
            } catch (RegraNegocioException qrInvalido) {
                // O erro do QR já é a mensagem mostrada.
            }
        }
        return "presenca-confirmar";
    }

    /** "Desvincular este celular": tira todas as pessoas dele e volta para a mesma tela. */
    @PostMapping("/presenca/desvincular")
    public String desvincular(@RequestParam Long sessaoId, @RequestParam String codigo,
            HttpServletRequest request, HttpServletResponse response) {
        CookieCelular.ler(request).forEach(celularVinculadoService::desvincular);
        CookieCelular.apagar(request, response);
        return "redirect:/presenca/" + sessaoId + "/" + codigo;
    }

    private void preparar(Model model, Long sessaoId, String codigo, Quem quem, Assistido marcado) {
        model.addAttribute("sessaoId", sessaoId);
        model.addAttribute("codigo", codigo);
        model.addAttribute("logado", quem.logado());
        model.addAttribute("doCelular", quem.doCelular().stream().map(PessoaDoCelular::assistido).toList());
        // Quem ainda pode ser marcado nesta tela: todos, menos quem acabou de ser (na tela do resultado,
        // os outros da família continuam com o seu botão).
        model.addAttribute("pessoas", quem.todos().stream()
                .filter(a -> marcado == null || !a.getId().equals(marcado.getId())).toList());
        model.addAttribute("marcado", marcado);
        model.addAttribute("podeLembrar", quem.logado() != null && !quem.vinculadoAoCelular(quem.logado()));
    }

    private Quem identificar(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        List<String> tokens = CookieCelular.ler(request);
        Assistido logado = null;
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            logado = assistidoRepository.findByLoginAndAcessoAtivoTrue(authentication.getName()).orElse(null);
        }
        List<PessoaDoCelular> doCelular = celularVinculadoService.identificar(tokens);
        // Regrava o cookie: renova a validade e descarta os tokens que a recepção desvinculou.
        if (!tokens.isEmpty()) {
            CookieCelular.gravar(request, response, doCelular.stream().map(PessoaDoCelular::token).toList());
        }
        return new Quem(logado, doCelular);
    }

    /** Quem esta tela pode marcar: o dono do login e as pessoas vinculadas a este celular. */
    private record Quem(Assistido logado, List<PessoaDoCelular> doCelular) {
        List<Assistido> todos() {
            List<Assistido> todos = new ArrayList<>();
            if (logado != null) {
                todos.add(logado);
            }
            doCelular.stream().map(PessoaDoCelular::assistido)
                    .filter(a -> logado == null || !a.getId().equals(logado.getId()))
                    .forEach(todos::add);
            return todos;
        }

        boolean ninguem() {
            return todos().isEmpty();
        }

        boolean vinculadoAoCelular(Assistido assistido) {
            return doCelular.stream().anyMatch(p -> p.assistido().getId().equals(assistido.getId()));
        }
    }
}
