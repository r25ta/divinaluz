package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SituacaoPrelecao;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import br.com.nae.divinaluz.service.CheckinService;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Controller
public class PrelecaoController {

    private final PrelecaoRepository prelecaoRepository;
    private final TrabalhadorRepository trabalhadorRepository;
    private final CheckinService checkinService;

    public PrelecaoController(PrelecaoRepository prelecaoRepository, TrabalhadorRepository trabalhadorRepository,
            CheckinService checkinService) {
        this.prelecaoRepository = prelecaoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
        this.checkinService = checkinService;
    }

    /**
     * Item da escala já classificado para a view (ver {@link SituacaoPrelecao}). A ordenação
     * coloca primeiro o que ainda vai acontecer — esta semana, depois as agendadas — e por último
     * o histórico, da mais recente para a mais antiga.
     */
    public record ItemEscala(Prelecao prelecao, SituacaoPrelecao situacao) {}

    @GetMapping("/prelecao")
    public String listarPrelecoes(Model model) {
        LocalDate hoje = LocalDate.now();
        List<ItemEscala> classificadas = prelecaoRepository.findAllByOrderByDataApresentacaoAsc().stream()
                .map(prelecao -> new ItemEscala(prelecao, SituacaoPrelecao.de(prelecao.getDataApresentacao(), hoje)))
                .toList();

        // Primeiro o que ainda vai acontecer (repositório já devolve por data crescente) e depois o
        // histórico invertido, para a preleção mais recente ficar no topo do passado.
        List<ItemEscala> escala = new ArrayList<>(porSituacao(classificadas, SituacaoPrelecao.ESTA_SEMANA));
        escala.addAll(porSituacao(classificadas, SituacaoPrelecao.AGENDADA));
        List<ItemEscala> realizadas = new ArrayList<>(porSituacao(classificadas, SituacaoPrelecao.REALIZADA));
        Collections.reverse(realizadas);
        escala.addAll(realizadas);

        // Destaque do topo: o que acontece nesta semana de assistência ou, se a semana já passou,
        // a próxima preleção agendada — para o assistido nunca cair num destaque vazio.
        List<ItemEscala> daSemana = porSituacao(classificadas, SituacaoPrelecao.ESTA_SEMANA);
        List<ItemEscala> destaques = daSemana;
        if (destaques.isEmpty()) {
            destaques = porSituacao(classificadas, SituacaoPrelecao.AGENDADA).stream().limit(1).toList();
        }

        model.addAttribute("escala", escala);
        model.addAttribute("hoje", hoje);
        model.addAttribute("sessaoAberta", checkinService.sessaoComCheckinAberto().orElse(null));
        model.addAttribute("destaques", destaques);
        model.addAttribute("destaqueNaSemana", !daSemana.isEmpty());
        model.addAttribute("totalEstaSemana", daSemana.size());
        model.addAttribute("totalAgendadas", porSituacao(classificadas, SituacaoPrelecao.AGENDADA).size());
        model.addAttribute("totalRealizadas", realizadas.size());
        return "prelecao-lista";
    }

    private List<ItemEscala> porSituacao(List<ItemEscala> escala, SituacaoPrelecao situacao) {
        return escala.stream().filter(item -> item.situacao() == situacao).toList();
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
