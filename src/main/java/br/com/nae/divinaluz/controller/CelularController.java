package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.config.CookieCelular;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.service.CelularVinculadoService;
import br.com.nae.divinaluz.service.QrCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Base64;
import java.util.List;

/**
 * Vincular o celular ao cartão para marcar presença sem login (ver CelularVinculadoService).
 *
 * <p>A recepção gera, no prontuário, um QR de vínculo (uso único, 10 minutos); a pessoa o escaneia
 * com o próprio celular e confirma. É o caminho de quem não tem login — quem tem pode também
 * vincular sozinho, marcando "lembrar este celular" ao confirmar a presença logado.</p>
 *
 * <p>O lado do celular fica sob {@code /presenca/vincular}, e não numa rota própria, porque o
 * cookie é restrito a {@code /presenca}: só assim ele chega aqui e a pessoa é <em>acrescentada</em>
 * às que já usam aquele celular, em vez de substituí-las.</p>
 */
@Controller
public class CelularController {

    private final CelularVinculadoService celularVinculadoService;
    private final AssistidoRepository assistidoRepository;
    private final QrCodeService qrCodeService;

    public CelularController(CelularVinculadoService celularVinculadoService,
            AssistidoRepository assistidoRepository, QrCodeService qrCodeService) {
        this.celularVinculadoService = celularVinculadoService;
        this.assistidoRepository = assistidoRepository;
        this.qrCodeService = qrCodeService;
    }

    // ------------------------------------------------------------------ recepção (permissão CADASTRO)

    /** Gera o convite e mostra o QR. POST porque cada abertura invalida o convite anterior. */
    @PostMapping("/prontuario/{assistidoId}/celular/convite")
    public String gerarConvite(@PathVariable Long assistidoId, Model model, RedirectAttributes redirectAttributes) {
        Assistido assistido = buscar(assistidoId);
        String convite;
        try {
            convite = celularVinculadoService.gerarConvite(assistido);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/prontuario/" + assistidoId;
        }
        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/presenca/vincular/{convite}").buildAndExpand(convite).toUriString();
        model.addAttribute("assistido", assistido);
        model.addAttribute("url", url);
        model.addAttribute("qrDataUri", "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(qrCodeService.png(url, 360)));
        model.addAttribute("validadeMinutos", CelularVinculadoService.VALIDADE_CONVITE_MINUTOS);
        model.addAttribute("vinculados", celularVinculadoService.quantosVinculados(assistidoId));
        return "celular-convite";
    }

    @PostMapping("/prontuario/{assistidoId}/celular/desvincular")
    public String desvincularTodos(@PathVariable Long assistidoId, RedirectAttributes redirectAttributes) {
        Assistido assistido = buscar(assistidoId);
        long quantos = celularVinculadoService.desvincularTodos(assistidoId);
        redirectAttributes.addFlashAttribute("sucesso", quantos == 0
                ? assistido.getNome() + " não tinha celular vinculado."
                : (quantos == 1 ? "1 celular desvinculado" : quantos + " celulares desvinculados")
                        + " de " + assistido.getNome() + ". Para marcar presença pelo QR será preciso vincular de novo.");
        return "redirect:/prontuario/" + assistidoId;
    }

    // ------------------------------------------------------------------ celular da pessoa (público)

    @GetMapping("/presenca/vincular/{convite}")
    public String confirmarVinculo(@PathVariable String convite, Model model) {
        try {
            model.addAttribute("assistido", celularVinculadoService.conferirConvite(convite));
            model.addAttribute("convite", convite);
        } catch (RegraNegocioException e) {
            model.addAttribute("erro", e.getMessage());
        }
        return "celular-vincular";
    }

    @PostMapping("/presenca/vincular/{convite}")
    public String vincular(@PathVariable String convite, HttpServletRequest request, HttpServletResponse response,
            Model model) {
        try {
            Assistido assistido = celularVinculadoService.conferirConvite(convite);
            List<String> tokens = celularVinculadoService.aceitarConvite(convite,
                    celularVinculadoService.identificar(CookieCelular.ler(request)));
            CookieCelular.gravar(request, response, tokens);
            model.addAttribute("assistido", assistido);
            model.addAttribute("vinculado", true);
        } catch (RegraNegocioException e) {
            model.addAttribute("erro", e.getMessage());
        }
        return "celular-vincular";
    }

    private Assistido buscar(Long assistidoId) {
        return assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
    }
}
