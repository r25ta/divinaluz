package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.exception.CartaoExpiradoException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PosicaoSessao;
import br.com.nae.divinaluz.model.SessaoAssistencia;
import br.com.nae.divinaluz.service.CheckinService;
import br.com.nae.divinaluz.service.SessaoService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Módulo Sessão (ver CLAUDE.md 3.13): lista das sessões, painel da sessão com check-in, escala,
 * preletor, indicadores em tempo real e a recepção (busca por nome e cadastro rápido). Todas as
 * rotas são de staff — caem na regra geral do SecurityConfig.
 */
@Controller
public class SessaoController {

    private final SessaoService sessaoService;
    private final CheckinService checkinService;

    public SessaoController(SessaoService sessaoService, CheckinService checkinService) {
        this.sessaoService = sessaoService;
        this.checkinService = checkinService;
    }

    @GetMapping("/sessao")
    public String listar(Model model) {
        LocalDate hoje = LocalDate.now();
        model.addAttribute("sessoes", sessaoService.listar());
        model.addAttribute("hoje", hoje);
        model.addAttribute("proximaData", SessaoService.proximaDataDeSessao(hoje));
        model.addAttribute("sessaoAberta", checkinService.sessaoComCheckinAberto().orElse(null));
        return "sessao-lista";
    }

    /** Abre (ou cria, se ainda não existe) a sessão da data e leva ao painel dela. */
    @PostMapping("/sessao")
    public String abrirSessao(@RequestParam(required = false) @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate data,
            RedirectAttributes redirectAttributes) {
        try {
            SessaoAssistencia sessao = sessaoService.garantirSessao(data);
            return "redirect:/sessao/" + sessao.getId();
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/sessao";
        }
    }

    @GetMapping("/sessao/{sessaoId}")
    public String painel(@PathVariable Long sessaoId, @RequestParam(required = false) String busca, Model model) {
        SessaoAssistencia sessao = sessaoService.buscar(sessaoId);
        SessaoService.Indicadores indicadores = sessaoService.indicadores(sessao);
        List<Assistido> resultados = sessaoService.buscarAssistidos(busca);
        Set<Long> presentesIds = indicadores.presentes().stream()
                .map(p -> p.assistido().getId())
                .collect(Collectors.toSet());

        model.addAttribute("sessao", sessao);
        model.addAttribute("hoje", LocalDate.now());
        model.addAttribute("preletor", sessaoService.preletorDaSessao(sessao));
        model.addAttribute("quadroEscala", sessaoService.quadroEscala(sessao));
        model.addAttribute("posicoes", PosicaoSessao.values());
        model.addAttribute("trabalhadores", sessaoService.trabalhadoresDisponiveis());
        model.addAttribute("indicadores", indicadores);
        model.addAttribute("busca", busca);
        model.addAttribute("resultados", resultados);
        model.addAttribute("presentesIds", presentesIds);
        return "sessao-painel";
    }

    /** Só o bloco de indicadores + presentes, que o painel recarrega sozinho a cada poucos segundos. */
    @GetMapping("/sessao/{sessaoId}/indicadores")
    public String indicadores(@PathVariable Long sessaoId, Model model) {
        SessaoAssistencia sessao = sessaoService.buscar(sessaoId);
        model.addAttribute("sessao", sessao);
        model.addAttribute("indicadores", sessaoService.indicadores(sessao));
        return "sessao-painel :: indicadores";
    }

    @PostMapping("/sessao/{sessaoId}/checkin/abrir")
    public String abrirCheckin(@PathVariable Long sessaoId, RedirectAttributes redirectAttributes) {
        try {
            checkinService.abrirCheckin(sessaoId, LocalDate.now());
            redirectAttributes.addFlashAttribute("sucesso",
                    "Check-in aberto. Escaneie o QR do cartão ou busque o assistido pelo nome.");
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/sessao/" + sessaoId;
    }

    @PostMapping("/sessao/{sessaoId}/checkin/fechar")
    public String fecharCheckin(@PathVariable Long sessaoId, RedirectAttributes redirectAttributes) {
        checkinService.fecharCheckin(sessaoId);
        redirectAttributes.addFlashAttribute("aviso", "Check-in encerrado. Não é mais possível marcar presença nesta sessão.");
        return "redirect:/sessao/" + sessaoId;
    }

    /** A casa não abriu: a sessão fica registrada como cancelada e as presenças da data são desfeitas. */
    @PostMapping("/sessao/{sessaoId}/cancelar")
    public String cancelar(@PathVariable Long sessaoId, @RequestParam(required = false) String motivo,
            RedirectAttributes redirectAttributes) {
        try {
            int desfeitas = sessaoService.cancelar(sessaoId, motivo);
            redirectAttributes.addFlashAttribute("aviso", "Sessão cancelada." + presencasDesfeitas(desfeitas)
                    + " Esta semana não conta como falta na regra dos 21 dias.");
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/sessao/" + sessaoId;
    }

    @PostMapping("/sessao/{sessaoId}/reativar")
    public String reativar(@PathVariable Long sessaoId, RedirectAttributes redirectAttributes) {
        sessaoService.reativar(sessaoId);
        redirectAttributes.addFlashAttribute("sucesso",
                "Sessão reativada. As presenças desfeitas no cancelamento precisam ser marcadas de novo.");
        return "redirect:/sessao/" + sessaoId;
    }

    /** Sessão aberta por engano: some da lista, com a escala, e as presenças da data são desfeitas. */
    @PostMapping("/sessao/{sessaoId}/excluir")
    public String excluir(@PathVariable Long sessaoId, RedirectAttributes redirectAttributes) {
        try {
            int desfeitas = sessaoService.excluir(sessaoId);
            redirectAttributes.addFlashAttribute("sucesso", "Sessão excluída." + presencasDesfeitas(desfeitas));
            return "redirect:/sessao";
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/sessao/" + sessaoId;
        }
    }

    private static String presencasDesfeitas(int pessoas) {
        return pessoas == 0 ? "" : " Presenças desfeitas: " + pessoas + (pessoas == 1 ? " pessoa." : " pessoas.");
    }

    @PostMapping("/sessao/{sessaoId}/preletor")
    public String trocarPreletor(@PathVariable Long sessaoId, @RequestParam(required = false) Long preletorId,
            @RequestParam(required = false) String tema, RedirectAttributes redirectAttributes) {
        sessaoService.trocarPreletor(sessaoId, preletorId, tema);
        redirectAttributes.addFlashAttribute("sucesso", preletorId == null && (tema == null || tema.isBlank())
                ? "Troca desfeita: vale o preletor da escala de preleções."
                : "Preletor desta sessão atualizado. A escala de preleções não foi alterada.");
        return "redirect:/sessao/" + sessaoId;
    }

    @PostMapping("/sessao/{sessaoId}/escala")
    public String escalar(@PathVariable Long sessaoId, @RequestParam(required = false) PosicaoSessao posicao,
            @RequestParam(required = false) Long trabalhadorId, RedirectAttributes redirectAttributes) {
        try {
            sessaoService.escalar(sessaoId, posicao, trabalhadorId);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/sessao/" + sessaoId + "#escala";
    }

    @PostMapping("/sessao/{sessaoId}/escala/{escalaId}/remover")
    public String removerDaEscala(@PathVariable Long sessaoId, @PathVariable Long escalaId) {
        sessaoService.removerDaEscala(sessaoId, escalaId);
        return "redirect:/sessao/" + sessaoId + "#escala";
    }

    @PostMapping("/sessao/{sessaoId}/presenca/{assistidoId}")
    public String marcarPresenca(@PathVariable Long sessaoId, @PathVariable Long assistidoId,
            @RequestParam(defaultValue = "false") boolean reiniciarP2, @RequestParam(required = false) String busca,
            RedirectAttributes redirectAttributes) {
        try {
            CheckinService.ResultadoCheckin resultado =
                    checkinService.registrarPresenca(sessaoId, assistidoId, reiniciarP2);
            avisarResultado(resultado, redirectAttributes);
        } catch (CartaoExpiradoException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            redirectAttributes.addFlashAttribute("expiradoId", assistidoId);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return voltarAoPainel(sessaoId, busca);
    }

    @PostMapping("/sessao/{sessaoId}/ouvinte/{assistidoId}")
    public String marcarOuvinte(@PathVariable Long sessaoId, @PathVariable Long assistidoId,
            @RequestParam(required = false) String busca, RedirectAttributes redirectAttributes) {
        try {
            redirectAttributes.addFlashAttribute("sucesso",
                    CheckinController.mensagemOuvinte(checkinService.registrarOuvinte(sessaoId, assistidoId)));
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return voltarAoPainel(sessaoId, busca);
    }

    /** Assistido pede para mudar o dia de assistência para o desta sessão (fica no histórico). */
    @PostMapping("/sessao/{sessaoId}/mudar-dia/{assistidoId}")
    public String mudarDiaEMarcarPresenca(@PathVariable Long sessaoId, @PathVariable Long assistidoId,
            @RequestParam(required = false) String busca, RedirectAttributes redirectAttributes) {
        try {
            CheckinService.ResultadoCheckin resultado = sessaoService.mudarDiaEMarcarPresenca(sessaoId, assistidoId);
            redirectAttributes.addFlashAttribute("aviso", "Dia de assistência de " + resultado.assistido().getNome()
                    + " alterado para " + resultado.sessao().getDiaFrequencia().getLabel() + " (registrado no histórico).");
            avisarResultado(resultado, redirectAttributes);
        } catch (CartaoExpiradoException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            redirectAttributes.addFlashAttribute("expiradoId", assistidoId);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return voltarAoPainel(sessaoId, busca);
    }

    @PostMapping("/sessao/{sessaoId}/cadastro-rapido")
    public String cadastroRapido(@PathVariable Long sessaoId, @RequestParam(required = false) String nome,
            @RequestParam(required = false) @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate dataNascimento,
            @RequestParam(required = false) String sexo,
            @RequestParam(defaultValue = "false") boolean confirmarHomonimo,
            RedirectAttributes redirectAttributes) {
        try {
            Assistido novo = sessaoService.cadastroRapido(sessaoId, nome, dataNascimento, sexo, confirmarHomonimo);
            redirectAttributes.addFlashAttribute("sucesso", novo.getNome()
                    + " cadastrado(a) em P2 com a 1ª presença marcada nesta sessão.");
            redirectAttributes.addFlashAttribute("aviso",
                    "Primeira vez na casa: encaminhe para a entrevista (opcional) e complete o cadastro no prontuário.");
            redirectAttributes.addFlashAttribute("novoAssistidoId", novo.getId());
        } catch (SessaoService.HomonimoException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            redirectAttributes.addFlashAttribute("homonimoNome", nome);
            redirectAttributes.addFlashAttribute("homonimoNascimento", dataNascimento);
            redirectAttributes.addFlashAttribute("homonimoSexo", sexo);
            return voltarAoPainel(sessaoId, nome) + "#recepcao";
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }
        return voltarAoPainel(sessaoId, null) + "#recepcao";
    }

    private void avisarResultado(CheckinService.ResultadoCheckin resultado, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("sucesso", CheckinController.mensagemDeSucesso(resultado));
        if (resultado.tratamentoReiniciado()) {
            redirectAttributes.addFlashAttribute("aviso",
                    "O assistido ficou 3 semanas ou mais sem sessão: o cartão anterior expirou e o tratamento foi reiniciado em P2.");
        }
    }

    private String voltarAoPainel(Long sessaoId, String busca) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath("/sessao/{sessaoId}");
        if (busca != null && !busca.isBlank()) {
            url.queryParam("busca", busca.trim());
        }
        return "redirect:" + url.buildAndExpand(sessaoId).encode().toUriString();
    }
}
