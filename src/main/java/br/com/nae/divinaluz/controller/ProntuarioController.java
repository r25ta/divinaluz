package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.exception.AvaliacaoPendenteException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Avaliacao;
import br.com.nae.divinaluz.model.Evolucao;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.service.TratamentoService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/")
public class ProntuarioController {

    private final AssistidoRepository assistidoRepository;

    private final SessaoRepository sessaoRepository;

    private final AvaliacaoRepository avaliacaoRepository;

    private final TratamentoService tratamentoService;

    private final TipoTratamentoRepository tipoTratamentoRepository;

    ProntuarioController(AssistidoRepository assistidoRepository, SessaoRepository sessaoRepository,
            AvaliacaoRepository avaliacaoRepository, TratamentoService tratamentoService,
            TipoTratamentoRepository tipoTratamentoRepository) {
        this.assistidoRepository = assistidoRepository;
        this.sessaoRepository = sessaoRepository;
        this.avaliacaoRepository = avaliacaoRepository;
        this.tratamentoService = tratamentoService;
        this.tipoTratamentoRepository = tipoTratamentoRepository;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("assistidos", assistidoRepository.findAll());
        return "index";
    }

    @GetMapping("/novo")
    public String novo(Model model) {
        model.addAttribute("assistido", new Assistido());
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());
        return "form";
    }

    @PostMapping("/salvar")
    public String salvar(@ModelAttribute Assistido assistido) {
        assistidoRepository.save(assistido);
        return "redirect:/";
    }

    // --- NOVO MÉTODO ADICIONADO AQUI ---
    @GetMapping("/prontuario/{id}")
    public String verProntuario(@PathVariable Long id, Model model) {
        // Busca o assistido pelo ID
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));
        
        // Busca o histórico de sessões ordenado pela data
        List<SessaoTratamento> sessoes = sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(id);

        List<Avaliacao> avaliacoes = avaliacaoRepository.findByAssistidoIdOrderByDataDesc(id);

        model.addAttribute("assistido", assistido);
        model.addAttribute("sessoes", sessoes);
        model.addAttribute("avaliacoes", avaliacoes);
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());

        return "prontuario"; // Nome do novo arquivo HTML
    }

    @PostMapping("/prontuario/{id}/tratamento")
    public String atualizarTratamento(@PathVariable Long id, @RequestParam(required = false) Long tratamentoAtualId) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));

        TipoTratamento tratamento = tratamentoAtualId != null
                ? tipoTratamentoRepository.findById(tratamentoAtualId).orElse(null)
                : null;
        assistido.setTratamentoAtual(tratamento);
        assistidoRepository.save(assistido);

        return "redirect:/prontuario/" + id;
    }

    @GetMapping("/prontuario/{id}/nova-sessao")
    public String novaSessao(@PathVariable Long id, Model model) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));

        model.addAttribute("assistido", assistido);
        model.addAttribute("sessao", new SessaoTratamento());
        return "sessao-form";
    }

    // O path variable não pode se chamar "id": o ExtendedServletRequestDataBinder do Spring MVC
    // usaria esse valor para preencher automaticamente o campo "id" do SessaoTratamento vindo do
    // form, fazendo o save() tentar um UPDATE em vez de um INSERT.
    @PostMapping("/prontuario/{assistidoId}/sessao")
    public String salvarSessao(@PathVariable Long assistidoId, @ModelAttribute SessaoTratamento sessao,
            RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
        sessao.setAssistido(assistido);

        try {
            TratamentoService.ResultadoSessao resultado = tratamentoService.registrarSessao(sessao);
            if (resultado.tratamentoReiniciado()) {
                redirectAttributes.addFlashAttribute("aviso",
                        "O assistido ficou 3 semanas ou mais sem sessão. O tratamento foi reiniciado em P2. "
                                + "Uma nova entrevista é recomendada (opcional).");
            }
        } catch (AvaliacaoPendenteException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            redirectAttributes.addFlashAttribute("avaliacaoPendente", true);
            return "redirect:/prontuario/" + assistidoId + "/nova-sessao";
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/prontuario/" + assistidoId + "/nova-sessao";
        }

        return "redirect:/prontuario/" + assistidoId;
    }

    @GetMapping("/prontuario/{id}/nova-avaliacao")
    public String novaAvaliacao(@PathVariable Long id, Model model) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));

        Avaliacao avaliacao = new Avaliacao();
        avaliacao.setTratamentoIndicado(assistido.getTratamentoAtual());

        model.addAttribute("assistido", assistido);
        model.addAttribute("avaliacao", avaliacao);
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());
        model.addAttribute("evolucoes", Evolucao.values());
        return "avaliacao-form";
    }

    // Mesmo cuidado do endpoint de sessão: path variable não pode se chamar "id" porque
    // Avaliacao também tem um campo "id".
    @PostMapping("/prontuario/{assistidoId}/avaliacao")
    public String salvarAvaliacao(@PathVariable Long assistidoId, @ModelAttribute Avaliacao avaliacao) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
        avaliacao.setAssistido(assistido);

        tratamentoService.registrarAvaliacao(avaliacao);

        return "redirect:/prontuario/" + assistidoId;
    }
}