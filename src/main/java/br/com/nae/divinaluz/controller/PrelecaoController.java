package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Controller
public class PrelecaoController {

    private final PrelecaoRepository prelecaoRepository;
    private final TrabalhadorRepository trabalhadorRepository;

    public PrelecaoController(PrelecaoRepository prelecaoRepository, TrabalhadorRepository trabalhadorRepository) {
        this.prelecaoRepository = prelecaoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
    }

    @GetMapping("/prelecao")
    public String listarPrelecoes(Model model) {
        model.addAttribute("prelecoes", prelecaoRepository.findAllByOrderByDataApresentacaoAsc());
        return "prelecao-lista";
    }

    @GetMapping("/prelecao/novo")
    public String novaPrelecao(Model model) {
        model.addAttribute("prelecao", new Prelecao());
        model.addAttribute("preletores", preletoresDisponiveis());
        return "prelecao-form";
    }

    @GetMapping("/prelecao/{prelecaoId}/editar")
    public String editarPrelecao(@PathVariable Long prelecaoId, Model model) {
        Prelecao prelecao = prelecaoRepository.findById(prelecaoId)
                .orElseThrow(() -> new IllegalArgumentException("Preleção inválida: " + prelecaoId));
        model.addAttribute("prelecao", prelecao);
        model.addAttribute("preletores", preletoresDisponiveis());
        return "prelecao-form";
    }

    @PostMapping("/prelecao/salvar")
    public String salvarPrelecao(@ModelAttribute Prelecao prelecao, @RequestParam Long preletorId,
                                RedirectAttributes redirectAttributes, Model model) {
        try {
            Trabalhador preletor = trabalhadorRepository.findById(preletorId)
                    .orElseThrow(() -> new IllegalArgumentException("Preletor inválido: " + preletorId));
            prelecao.setPreletor(preletor);
            validarPreletor(preletor);
            validarDataPrelecao(prelecao.getDataApresentacao());
            validarUnicidadeDaData(prelecao.getDataApresentacao(), null);

            prelecaoRepository.save(prelecao);
            return "redirect:/prelecao";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            prelecao.setDataApresentacao(null);
            prepararFormulario(model, prelecao);
            model.addAttribute("erro", e.getMessage());
            return "prelecao-form";
        }
    }

    @PostMapping("/prelecao/{prelecaoId}/editar")
    public String atualizarPrelecao(@PathVariable Long prelecaoId, @ModelAttribute Prelecao dadosPrelecao,
                                   @RequestParam Long preletorId, RedirectAttributes redirectAttributes, Model model) {
        try {
            Prelecao prelecao = prelecaoRepository.findById(prelecaoId)
                    .orElseThrow(() -> new IllegalArgumentException("Preleção inválida: " + prelecaoId));

            Trabalhador preletor = trabalhadorRepository.findById(preletorId)
                    .orElseThrow(() -> new IllegalArgumentException("Preletor inválido: " + preletorId));
            prelecao.setDataApresentacao(dadosPrelecao.getDataApresentacao());
            prelecao.setTema(dadosPrelecao.getTema());
            prelecao.setPreletor(preletor);
            validarPreletor(preletor);
            validarDataPrelecao(prelecao.getDataApresentacao());
            validarUnicidadeDaData(prelecao.getDataApresentacao(), prelecaoId);

            prelecaoRepository.save(prelecao);
            return "redirect:/prelecao";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            dadosPrelecao.setId(prelecaoId);
            dadosPrelecao.setDataApresentacao(null);
            prepararFormulario(model, dadosPrelecao);
            model.addAttribute("erro", e.getMessage());
            return "prelecao-form";
        }
    }

    @PostMapping("/prelecao/{prelecaoId}/excluir")
    public String excluirPrelecao(@PathVariable Long prelecaoId) {
        prelecaoRepository.deleteById(prelecaoId);
        return "redirect:/prelecao";
    }

    private List<Trabalhador> preletoresDisponiveis() {
        return trabalhadorRepository.findAll().stream()
                .filter(trabalhador -> trabalhador.getFuncoes() != null
                        && trabalhador.getFuncoes().contains(TipoTrabalhador.EXPOSITOR_PRELETOR))
                .sorted(Comparator.comparing(t -> t.getAssistido().getNome()))
                .toList();
    }

    private void validarPreletor(Trabalhador preletor) {
        if (preletor == null || preletor.getAssistido() == null) {
            throw new IllegalArgumentException("Selecione um preletor válido.");
        }

        if (preletor.getFuncoes() == null || !preletor.getFuncoes().contains(TipoTrabalhador.EXPOSITOR_PRELETOR)) {
            throw new IllegalArgumentException("O preletor selecionado precisa ter a função de Expositor / Preletor.");
        }
    }

    private void validarDataPrelecao(LocalDate data) {
        if (data == null) {
            throw new IllegalArgumentException("Informe a data da apresentação.");
        }

        DayOfWeek dia = data.getDayOfWeek();
        if (dia != DayOfWeek.TUESDAY && dia != DayOfWeek.SUNDAY) {
            throw new IllegalArgumentException("A preleção só pode ser cadastrada em Terça-feira ou Domingo.");
        }
    }

    private void validarUnicidadeDaData(LocalDate data, Long prelecaoIdIgnorada) {
        Optional<Prelecao> existente = prelecaoRepository.findByDataApresentacao(data);
        if (existente.isPresent() && !existente.get().getId().equals(prelecaoIdIgnorada)) {
            throw new IllegalArgumentException("Já existe uma preleção cadastrada para esta data. Só pode haver uma por sessão.");
        }
    }

    private void prepararFormulario(Model model, Prelecao prelecao) {
        model.addAttribute("prelecao", prelecao);
        model.addAttribute("preletores", preletoresDisponiveis());
    }
}
