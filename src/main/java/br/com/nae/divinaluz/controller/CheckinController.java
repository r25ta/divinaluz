package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.exception.AvaliacaoPendenteException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.service.CheckinService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/**
 * Check-in por QR code, do lado da recepção (ver CLAUDE.md 3.12). O QR está no cartão do assistido;
 * quem escaneia é a recepção, já autenticada como staff — por isso todas as rotas daqui caem na
 * regra de staff do SecurityConfig, inclusive a tela de confirmação do scan.
 */
@Controller
public class CheckinController {

    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @PostMapping("/prelecao/{prelecaoId}/checkin/abrir")
    public String abrirCheckin(@PathVariable Long prelecaoId, RedirectAttributes redirectAttributes) {
        try {
            Prelecao sessao = checkinService.abrirCheckin(prelecaoId, LocalDate.now());
            redirectAttributes.addFlashAttribute("aviso",
                    "Check-in aberto para a sessão de " + sessao.getTema() + ". Escaneie o QR do cartão de cada assistido.");
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/prelecao";
    }

    @PostMapping("/prelecao/{prelecaoId}/checkin/fechar")
    public String fecharCheckin(@PathVariable Long prelecaoId, RedirectAttributes redirectAttributes) {
        checkinService.fecharCheckin(prelecaoId);
        redirectAttributes.addFlashAttribute("aviso", "Check-in encerrado. O QR não carimba mais presença.");
        return "redirect:/prelecao";
    }

    /** Tela que abre quando a recepção escaneia o QR do cartão: mostra quem é e o que fazer. */
    @GetMapping("/checkin/{codigoCartao}")
    public String confirmarCheckin(@PathVariable String codigoCartao, Model model) {
        try {
            Assistido assistido = checkinService.buscarPorCodigoCartao(codigoCartao);
            model.addAttribute("assistido", assistido);
            model.addAttribute("codigoCartao", codigoCartao);
        } catch (RegraNegocioException e) {
            model.addAttribute("erro", e.getMessage());
            return "checkin-confirmar";
        }

        Prelecao sessao = checkinService.sessaoComCheckinAberto().orElse(null);
        model.addAttribute("sessaoAberta", sessao);
        if (sessao == null) {
            model.addAttribute("erro",
                    "Nenhuma sessão está com o check-in aberto. Abra o check-in da sessão de hoje na escala de preleções.");
        }
        return "checkin-confirmar";
    }

    @PostMapping("/checkin/{codigoCartao}")
    public String registrarPresenca(@PathVariable String codigoCartao, RedirectAttributes redirectAttributes) {
        try {
            CheckinService.ResultadoCheckin resultado = checkinService.registrarPresenca(codigoCartao);
            redirectAttributes.addFlashAttribute("sucesso", mensagemDeSucesso(resultado));
            if (resultado.tratamentoReiniciado()) {
                redirectAttributes.addFlashAttribute("aviso",
                        "O assistido ficou 3 semanas ou mais sem sessão: o cartão anterior expirou e o tratamento foi reiniciado em P2.");
            }
        } catch (AvaliacaoPendenteException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/checkin/" + codigoCartao;
    }

    /**
     * Presença como ouvinte por decisão da recepção — saída prevista para quem comparece num dia que
     * não é o seu (o cartão dele não avança).
     */
    @PostMapping("/checkin/{codigoCartao}/ouvinte")
    public String registrarOuvinte(@PathVariable String codigoCartao, RedirectAttributes redirectAttributes) {
        try {
            CheckinService.ResultadoCheckin resultado = checkinService.registrarOuvinte(codigoCartao);
            redirectAttributes.addFlashAttribute("sucesso", resultado.assistido().getNome()
                    + " entrou como ouvinte na sessão de "
                    + resultado.prelecao().getTema() + ". O cartão não avançou.");
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/checkin/" + codigoCartao;
    }

    private String mensagemDeSucesso(CheckinService.ResultadoCheckin resultado) {
        String nome = resultado.assistido().getNome();
        if (resultado.ouvinte()) {
            return nome + " já tinha presença nesta semana: entrou como ouvinte e o cartão não avançou.";
        }
        Integer numero = resultado.sessao().getNumeroSerie();
        String presenca = numero != null ? numero + "ª presença" : "presença";
        String aviso = resultado.assistido().getStatusCartao() == CartaoStatus.AGUARDANDO_AVALIACAO
                ? " Cartão completo: encaminhe para a avaliação espiritual."
                : "";
        return presenca + " carimbada no cartão de " + nome + "." + aviso;
    }
}
