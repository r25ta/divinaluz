package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PerfilAcesso;
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
        model.addAttribute("perfis", PerfilAcesso.values());
        model.addAttribute("funcoesSelecionadas",
                trabalhadorRepository.findByAssistidoId(id)
                        .map(trabalhador -> trabalhador.getFuncoes())
                        .orElse(java.util.Set.of()));
        return "trabalhador-form";
    }

    /**
     * Salva as funções e, quando a pessoa já tem login, o perfil do acesso — os dois na mesma tela.
     *
     * <p><strong>Mudança de 2026-10-02, decidida pelo responsável do projeto:</strong> a troca de
     * perfil existia só em {@code /prontuario/{id}/acesso} e era exclusiva do Administrador, e aqui
     * só havia um aviso mandando ir até lá. Isso confundia — o prontuário é dos tratamentos, não dos
     * perfis — e fazia a promoção parecer ter falhado quando havia funcionado. Agora quem alcança
     * este módulo (permissão {@code TRABALHADORES}, hoje só o Dirigente) também troca o perfil.</p>
     *
     * <p><strong>Consequência aceita:</strong> isto afrouxa a separação descrita em 3.17 do
     * CLAUDE.md. Promover alguém a Dirigente passa a conceder, por si só, o poder de tornar qualquer
     * pessoa staff, sem um Administrador no caminho. Era exatamente o que a separação evitava.</p>
     */
    @PostMapping("/trabalhadores/{assistidoId}")
    public String salvar(@PathVariable Long assistidoId,
            @RequestParam(required = false) List<TipoTrabalhador> funcoes,
            @RequestParam(required = false) PerfilAcesso perfilAcesso,
            RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));

        trabalhadorService.definirPerfis(assistido, funcoes);

        // Só mexe no perfil se a pessoa tem acesso: sem login não há o que trocar, e gravar um perfil
        // sem login deixaria a linha num estado que o UserDetailsService ignora de qualquer forma.
        boolean trocouPerfil = false;
        if (perfilAcesso != null && assistido.getLogin() != null
                && perfilAcesso != assistido.getPerfilAcesso()) {
            assistido.setPerfilAcesso(perfilAcesso);
            assistidoRepository.save(assistido);
            trocouPerfil = true;
        }

        boolean temFuncao = funcoes != null && !funcoes.isEmpty();
        String mensagem = temFuncao
                ? assistido.getNome() + " cadastrado(a) como trabalhador."
                : assistido.getNome() + " voltou a ser somente assistido.";
        if (trocouPerfil) {
            mensagem += " Perfil do acesso alterado para " + perfilAcesso.name()
                    + " — vale no próximo login dele(a).";
        }
        redirectAttributes.addFlashAttribute("sucesso", mensagem);

        // O aviso continua, mas agora só quando ainda há de fato algo pendente, e apontando para a
        // ação certa em vez de mandar ao prontuário.
        if (temFuncao && assistido.getPerfilAcesso() != PerfilAcesso.TRABALHADOR
                && assistido.getPerfilAcesso() != PerfilAcesso.ADMINISTRADOR) {
            redirectAttributes.addFlashAttribute("aviso", assistido.getNome()
                    + (assistido.getLogin() == null
                        ? " ainda não tem login, então as funções não liberam módulo nenhum. Crie o acesso pelo prontuário."
                        : " está com perfil de acesso \"" + assistido.getPerfilAcesso()
                          + "\", então as funções não liberam módulo nenhum. Mude o perfil para"
                          + " \"TRABALHADOR\" nesta mesma tela."));
        }
        return "redirect:/trabalhadores";
    }
}
