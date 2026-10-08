package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.exception.AvaliacaoPendenteException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.service.CheckinService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Check-in por QR code, do lado da recepção (ver CLAUDE.md 3.12). O QR está no cartão do assistido;
 * quem escaneia é a recepção, já autenticada como staff — por isso todas as rotas daqui caem na
 * regra de staff do SecurityConfig, inclusive a tela de confirmação do scan. A janela de check-in é
 * aberta e fechada no painel da sessão (SessaoController).
 */
@Controller
public class CheckinController {

    private final CheckinService checkinService;
    private final AvaliacaoRepository avaliacaoRepository;

    public CheckinController(CheckinService checkinService, AvaliacaoRepository avaliacaoRepository) {
        this.checkinService = checkinService;
        this.avaliacaoRepository = avaliacaoRepository;
    }

    /** Tela que abre quando a recepção escaneia o QR do cartão: mostra quem é e o que fazer. */
    @GetMapping("/checkin/{codigoCartao}")
    public String confirmarCheckin(@PathVariable String codigoCartao, Model model) {
        Assistido assistido;
        try {
            assistido = checkinService.buscarPorCodigoCartao(codigoCartao);
            model.addAttribute("assistido", assistido);
            model.addAttribute("codigoCartao", codigoCartao);
        } catch (RegraNegocioException e) {
            model.addAttribute("erro", e.getMessage());
            return "checkin-confirmar";
        }

        // Cartão retido em Aguardando Entrevista: a recepção encaminha para o entrevistador, que
        // precisa saber a qual avaliação essa entrevista se refere (ver Módulo de Entrevista).
        if (assistido.getStatusCartao() == CartaoStatus.AGUARDANDO_ENTREVISTA) {
            avaliacaoRepository.findFirstByAssistidoIdAndEntrevistaIsNullOrderByDataDesc(assistido.getId())
                    .ifPresent(avaliacao -> model.addAttribute("avaliacaoPendenteId", avaliacao.getId()));
        }

        SessaoAssistencia sessao = checkinService.sessaoComCheckinAberto().orElse(null);
        model.addAttribute("sessaoAberta", sessao);
        if (sessao == null) {
            model.addAttribute("erro", CheckinService.SEM_SESSAO_ABERTA);
        }
        return "checkin-confirmar";
    }

    @PostMapping("/checkin/{codigoCartao}")
    public String registrarPresenca(@PathVariable String codigoCartao, RedirectAttributes redirectAttributes) {
        return carimbar(codigoCartao, false, redirectAttributes);
    }

    // Reinício em P2 de cartão expirado: decisão da recepção (o assistido tem acesso só de consulta).
    @PostMapping("/checkin/{codigoCartao}/reiniciar")
    public String reiniciarEPresenca(@PathVariable String codigoCartao, RedirectAttributes redirectAttributes) {
        return carimbar(codigoCartao, true, redirectAttributes);
    }

    private String carimbar(String codigoCartao, boolean confirmarReinicio, RedirectAttributes redirectAttributes) {
        try {
            CheckinService.ResultadoCheckin resultado = checkinService.registrarPresenca(codigoCartao, confirmarReinicio);
            redirectAttributes.addFlashAttribute("sucesso", mensagemDeSucesso(resultado));
            if (resultado.tratamentoReiniciado()) {
                redirectAttributes.addFlashAttribute("aviso", mensagemReinicio(resultado));
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
            redirectAttributes.addFlashAttribute("sucesso", mensagemOuvinte(resultado));
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/checkin/" + codigoCartao;
    }

    static String mensagemOuvinte(CheckinService.ResultadoCheckin resultado) {
        return resultado.assistido().getNome() + " entrou como ouvinte na sessão de "
                + resultado.sessao().getDiaFrequencia().getLabel() + ". O cartão não avançou.";
    }

    static String mensagemReinicio(CheckinService.ResultadoCheckin resultado) {
        return resultado.aposAlta()
                ? "Depois da alta, um novo tratamento foi iniciado em P2 e esta é a 1ª sessão do novo cartão."
                : "O assistido ficou 3 semanas ou mais sem sessão: o cartão anterior expirou e o tratamento foi reiniciado em P2.";
    }

    static String mensagemDeSucesso(CheckinService.ResultadoCheckin resultado) {
        String nome = resultado.assistido().getNome();
        if (resultado.ouvinte() && resultado.aposAlta()) {
            return nome + " recebeu alta e entrou como ouvinte. Para um novo tratamento, use \"Novo tratamento em P2\".";
        }
        if (resultado.ouvinte()) {
            return nome + " já tinha presença nesta semana: entrou como ouvinte e o cartão não avançou.";
        }
        Integer numero = resultado.presenca().getNumeroSerie();
        String presenca = numero != null ? numero + "ª presença" : "presença";
        String aviso = resultado.assistido().getStatusCartao() == CartaoStatus.AGUARDANDO_AVALIACAO
                ? " Cartão completo e em avaliação: encaminhe para o Avaliador."
                : "";
        return presenca + " carimbada no cartão de " + nome + "." + aviso;
    }
}
