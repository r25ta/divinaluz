package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import br.com.nae.divinaluz.service.TrabalhadorService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Módulo "Cadastrar Trabalhador" (substituiu a antiga tela /usuarios). Como todo trabalhador é
 * antes um assistido, aqui não se cadastra ninguém do zero: busca-se um assistido já cadastrado e
 * escolhem-se os perfis de trabalho dele (ver {@link TrabalhadorService}).
 */
@Controller
public class TrabalhadorController {

    private final TrabalhadorService trabalhadorService;
    private final TrabalhadorRepository trabalhadorRepository;
    private final AssistidoRepository assistidoRepository;

    public TrabalhadorController(TrabalhadorService trabalhadorService,
            TrabalhadorRepository trabalhadorRepository, AssistidoRepository assistidoRepository) {
        this.trabalhadorService = trabalhadorService;
        this.trabalhadorRepository = trabalhadorRepository;
        this.assistidoRepository = assistidoRepository;
    }

    @GetMapping("/trabalhadores")
    public String listar(@RequestParam(required = false) String busca, Model model) {
        model.addAttribute("trabalhadores", trabalhadorService.listarTrabalhadores());
        model.addAttribute("busca", busca);
        model.addAttribute("resultados", trabalhadorService.buscarAssistidos(busca));
        model.addAttribute("buscou", busca != null && !busca.isBlank());
        return "trabalhador-lista";
    }

    /** Rota antiga da tela de usuários — agora este módulo. */
    @GetMapping("/usuarios")
    public String usuariosRedireciona() {
        return "redirect:/trabalhadores";
    }

    @GetMapping("/trabalhadores/{id}")
    public String perfis(@PathVariable Long id, Model model) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));

        model.addAttribute("assistido", assistido);
        model.addAttribute("funcoes", TipoTrabalhador.values());
        model.addAttribute("funcoesSelecionadas",
                trabalhadorRepository.findByAssistidoId(id)
                        .map(trabalhador -> trabalhador.getFuncoes())
                        .orElse(java.util.Set.of()));
        return "trabalhador-form";
    }

    /**
     * Salva as funções; o perfil do acesso acompanha sozinho (ver
     * {@link TrabalhadorService#definirPerfis}). Desde 2026-10-03 não há mais seletor de perfil
     * nesta tela: promover a trabalhador já é a decisão de torná-lo staff.
     */
    @PostMapping("/trabalhadores/{assistidoId}")
    public String salvar(@PathVariable Long assistidoId,
            @RequestParam(required = false) List<TipoTrabalhador> funcoes,
            RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));

        boolean trocouPerfil = trabalhadorService.definirPerfis(assistido, funcoes);

        boolean temFuncao = funcoes != null && !funcoes.isEmpty();
        String mensagem = temFuncao
                ? assistido.getNome() + " cadastrado(a) como trabalhador."
                : assistido.getNome() + " voltou a ser somente assistido.";
        if (trocouPerfil) {
            mensagem += " Perfil do acesso alterado para " + assistido.getPerfilAcesso().name()
                    + " — vale no próximo login dele(a).";
        }
        redirectAttributes.addFlashAttribute("sucesso", mensagem);

        // Sem login não há perfil a acompanhar: as funções ficam registradas, mas só valem com acesso.
        if (temFuncao && assistido.getLogin() == null) {
            redirectAttributes.addFlashAttribute("aviso", assistido.getNome()
                    + " ainda não tem login, então as funções não liberam módulo nenhum. Crie o acesso pelo prontuário.");
        }
        return "redirect:/trabalhadores";
    }
}
