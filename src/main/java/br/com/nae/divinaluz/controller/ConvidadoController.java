package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.service.AcessoService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Preletor convidado no "Novo Cadastro" (V41, pedido do responsável do projeto em 2026-10-05): um
 * cadastro com vínculo CONVIDADO, só com dados de contato (nome, telefone, e-mail, origem) — sem
 * tratamento, 1ª sessão, cartão ou login. Fica <strong>fora</strong> da listagem de prontuários, da
 * busca da recepção e da promoção a trabalhador (AssistidoRepository.atendidosPorAtivo); a lista dele
 * é esta. Como todo cadastro, nunca é excluído: é desativado (sai da lista de escolha da escala).
 * Permissão CADASTRO, como o Novo Cadastro.
 */
@Controller
public class ConvidadoController {

    private final AssistidoRepository assistidoRepository;
    private final AcessoService acessoService;

    public ConvidadoController(AssistidoRepository assistidoRepository, AcessoService acessoService) {
        this.assistidoRepository = assistidoRepository;
        this.acessoService = acessoService;
    }

    @GetMapping("/convidados")
    public String listar(Model model) {
        model.addAttribute("convidados", assistidoRepository.findByVinculoOrderByNomeAsc(Assistido.VINCULO_CONVIDADO));
        return "convidado-lista";
    }

    @GetMapping("/convidados/novo")
    public String novo(Model model) {
        model.addAttribute("convidado", new Assistido());
        return "convidado-form";
    }

    @GetMapping("/convidados/{convidadoId}")
    public String editar(@PathVariable Long convidadoId, Model model) {
        model.addAttribute("convidado", buscar(convidadoId));
        return "convidado-form";
    }

    @PostMapping("/convidados")
    public String salvarNovo(@ModelAttribute("convidado") Assistido dados, Model model,
            RedirectAttributes redirectAttributes) {
        Assistido convidado = new Assistido();
        convidado.setVinculo(Assistido.VINCULO_CONVIDADO);
        // Sem tratamento, dia de assistência nem ciclo: o convidado não passa pelo cartão.
        return gravar(convidado, dados, model, redirectAttributes);
    }

    @PostMapping("/convidados/{convidadoId}")
    public String salvarEdicao(@PathVariable Long convidadoId, @ModelAttribute("convidado") Assistido dados,
            Model model, RedirectAttributes redirectAttributes) {
        return gravar(buscar(convidadoId), dados, model, redirectAttributes);
    }

    /** Desativar tira o convidado da lista de escolha da escala, sem mexer nas preleções dele. */
    @PostMapping("/convidados/{convidadoId}/ativo")
    public String alternarAtivo(@PathVariable Long convidadoId, RedirectAttributes redirectAttributes) {
        Assistido convidado = buscar(convidadoId);
        convidado.setAtivo(!convidado.isAtivo());
        assistidoRepository.save(convidado);
        redirectAttributes.addFlashAttribute("sucesso", convidado.getNome()
                + (convidado.isAtivo() ? " voltou a aparecer na escala." : " não aparece mais para escolher na escala."));
        return "redirect:/convidados";
    }

    private String gravar(Assistido convidado, Assistido dados, Model model, RedirectAttributes redirectAttributes) {
        String email = AcessoService.normalizarEmail(dados.getEmail());
        try {
            if (dados.getNome() == null || dados.getNome().trim().length() < 3) {
                throw new RegraNegocioException("Informe o nome do preletor convidado.");
            }
            acessoService.validarEmail(email, convidado.getId());
        } catch (RegraNegocioException e) {
            dados.setId(convidado.getId());
            dados.setEmail(email);
            model.addAttribute("convidado", dados);
            model.addAttribute("erro", e.getMessage());
            return "convidado-form";
        }
        convidado.setNome(limitar(dados.getNome().trim(), 255));
        convidado.setTelefone(opcional(dados.getTelefone(), 30));
        convidado.setOrigem(opcional(dados.getOrigem(), 150));
        convidado.setEmail(email);
        try {
            assistidoRepository.save(convidado);
        } catch (DataIntegrityViolationException e) {
            dados.setId(convidado.getId());
            model.addAttribute("convidado", dados);
            model.addAttribute("erro", "Este e-mail acabou de ser usado em outro cadastro. Confira e tente de novo.");
            return "convidado-form";
        }
        redirectAttributes.addFlashAttribute("sucesso", "Preletor convidado " + convidado.getNome()
                + " salvo. Ele já pode ser escolhido na escala de preleções.");
        return "redirect:/convidados";
    }

    private Assistido buscar(Long convidadoId) {
        return assistidoRepository.findById(convidadoId)
                .filter(Assistido::isConvidado)
                .orElseThrow(() -> new IllegalArgumentException("Convidado inválido: " + convidadoId));
    }

    private static String opcional(String valor, int maximo) {
        return valor != null && !valor.isBlank() ? limitar(valor.trim(), maximo) : null;
    }

    private static String limitar(String valor, int maximo) {
        return valor.length() > maximo ? valor.substring(0, maximo) : valor;
    }
}
