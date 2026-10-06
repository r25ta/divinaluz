package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.SituacaoPrelecao;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Escala de preleções (permissão PRELECAO: Dirigente, Recepcionista e Expositor/Preletor).
 *
 * <p>O preletor pode ser um <strong>trabalhador da casa</strong> (com a função Expositor/Preletor)
 * ou um <strong>convidado</strong> — um cadastro com vínculo CONVIDADO, feito pelo "Novo Cadastro"
 * (V41; ver ConvidadoController). No formulário os dois aparecem no mesmo select, com o valor
 * {@code T:<id>} ou {@code C:<id>}. O parâmetro se chama {@code preletorEscolhido}, e não
 * {@code preletor}, porque o {@code @ModelAttribute Prelecao} tentaria converter "T:12" no campo
 * {@code preletor} (um Trabalhador) e a requisição daria 400. A preleção também pode ser
 * <strong>cancelada</strong> (fica na escala com o motivo e libera a data) e reativada.</p>
 */
@Controller
public class PrelecaoController {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PrelecaoRepository prelecaoRepository;
    private final TrabalhadorRepository trabalhadorRepository;
    private final AssistidoRepository assistidoRepository;
    private final SessaoRepository sessaoRepository;
    private final CheckinService checkinService;

    public PrelecaoController(PrelecaoRepository prelecaoRepository, TrabalhadorRepository trabalhadorRepository,
            AssistidoRepository assistidoRepository, SessaoRepository sessaoRepository,
            CheckinService checkinService) {
        this.prelecaoRepository = prelecaoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
        this.assistidoRepository = assistidoRepository;
        this.sessaoRepository = sessaoRepository;
        this.checkinService = checkinService;
    }

    /**
     * Item da escala já classificado para a view (ver {@link SituacaoPrelecao}). A ordenação
     * coloca primeiro o que ainda vai acontecer — esta semana, depois as agendadas —, depois o
     * histórico, da mais recente para a mais antiga, e as canceladas no fim.
     */
    public record ItemEscala(Prelecao prelecao, SituacaoPrelecao situacao) {}

    @GetMapping("/prelecao")
    public String listarPrelecoes(Model model) {
        LocalDate hoje = LocalDate.now();
        List<ItemEscala> classificadas = prelecaoRepository.findAllByOrderByDataApresentacaoAsc().stream()
                .map(prelecao -> new ItemEscala(prelecao, SituacaoPrelecao.de(prelecao, hoje)))
                .toList();

        // Primeiro o que ainda vai acontecer (repositório já devolve por data crescente) e depois o
        // histórico invertido, para a preleção mais recente ficar no topo do passado.
        List<ItemEscala> escala = new ArrayList<>(porSituacao(classificadas, SituacaoPrelecao.ESTA_SEMANA));
        escala.addAll(porSituacao(classificadas, SituacaoPrelecao.AGENDADA));
        List<ItemEscala> realizadas = new ArrayList<>(porSituacao(classificadas, SituacaoPrelecao.REALIZADA));
        Collections.reverse(realizadas);
        escala.addAll(realizadas);
        List<ItemEscala> canceladas = porSituacao(classificadas, SituacaoPrelecao.CANCELADA);
        escala.addAll(canceladas);

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
        model.addAttribute("totalCanceladas", canceladas.size());
        return "prelecao-lista";
    }

    private List<ItemEscala> porSituacao(List<ItemEscala> escala, SituacaoPrelecao situacao) {
        return escala.stream().filter(item -> item.situacao() == situacao).toList();
    }

    @GetMapping("/prelecao/novo")
    public String novaPrelecao(Model model) {
        prepararFormulario(model, new Prelecao(), null);
        return "prelecao-form";
    }

    @GetMapping("/prelecao/{prelecaoId}/editar")
    public String editarPrelecao(@PathVariable Long prelecaoId, Model model) {
        Prelecao prelecao = buscar(prelecaoId);
        prepararFormulario(model, prelecao, escolhaAtual(prelecao));
        return "prelecao-form";
    }

    @PostMapping("/prelecao/salvar")
    public String salvarPrelecao(@ModelAttribute Prelecao prelecao,
                                 @RequestParam(required = false) String preletorEscolhido,
                                 RedirectAttributes redirectAttributes, Model model) {
        try {
            aplicarPreletor(prelecao, preletorEscolhido);
            validarDataPrelecao(prelecao.getDataApresentacao());
            validarUnicidadeDaData(prelecao.getDataApresentacao(), null);
            validarTema(prelecao.getTema());

            prelecaoRepository.save(prelecao);
            return "redirect:/prelecao";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            prelecao.setDataApresentacao(null);
            prepararFormulario(model, prelecao, preletorEscolhido);
            model.addAttribute("erro", e.getMessage());
            return "prelecao-form";
        }
    }

    @PostMapping("/prelecao/{prelecaoId}/editar")
    public String atualizarPrelecao(@PathVariable Long prelecaoId, @ModelAttribute Prelecao dadosPrelecao,
                                    @RequestParam(required = false) String preletorEscolhido,
                                    RedirectAttributes redirectAttributes, Model model) {
        try {
            Prelecao prelecao = buscar(prelecaoId);
            prelecao.setDataApresentacao(dadosPrelecao.getDataApresentacao());
            prelecao.setTema(dadosPrelecao.getTema());
            aplicarPreletor(prelecao, preletorEscolhido);
            validarDataPrelecao(prelecao.getDataApresentacao());
            if (!prelecao.isCancelada()) {
                validarUnicidadeDaData(prelecao.getDataApresentacao(), prelecaoId);
            }
            validarTema(prelecao.getTema());

            prelecaoRepository.save(prelecao);
            return "redirect:/prelecao";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            dadosPrelecao.setId(prelecaoId);
            dadosPrelecao.setDataApresentacao(null);
            prepararFormulario(model, dadosPrelecao, preletorEscolhido);
            model.addAttribute("erro", e.getMessage());
            return "prelecao-form";
        }
    }

    /** Cancelar: a preleção não vai acontecer. Fica na escala com o motivo, e a data fica livre. */
    @PostMapping("/prelecao/{prelecaoId}/cancelar")
    public String cancelarPrelecao(@PathVariable Long prelecaoId, @RequestParam(required = false) String motivo,
                                   RedirectAttributes redirectAttributes) {
        Prelecao prelecao = buscar(prelecaoId);
        prelecao.setCanceladaEm(LocalDateTime.now());
        prelecao.setMotivoCancelamento(preenchido(motivo) ? limitar(motivo.trim(), 255) : null);
        prelecaoRepository.save(prelecao);
        redirectAttributes.addFlashAttribute("sucesso", "Preleção de "
                + prelecao.getDataApresentacao().format(FORMATO_DATA)
                + " cancelada. A data ficou livre para outra preleção.");
        return "redirect:/prelecao";
    }

    @PostMapping("/prelecao/{prelecaoId}/reativar")
    public String reativarPrelecao(@PathVariable Long prelecaoId, RedirectAttributes redirectAttributes) {
        Prelecao prelecao = buscar(prelecaoId);
        String data = prelecao.getDataApresentacao().format(FORMATO_DATA);
        try {
            validarUnicidadeDaData(prelecao.getDataApresentacao(), prelecaoId);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", "Não dá para reativar: já existe outra preleção em "
                    + data + ". Cancele ou exclua aquela antes.");
            return "redirect:/prelecao";
        }
        prelecao.setCanceladaEm(null);
        prelecao.setMotivoCancelamento(null);
        prelecaoRepository.save(prelecao);
        redirectAttributes.addFlashAttribute("sucesso", "Preleção de " + data + " reativada.");
        return "redirect:/prelecao";
    }

    /**
     * Excluir é para o que foi cadastrado por engano. Preleção que já tem presenças ligadas é
     * histórico das sessões e fica (antes isso estourava a FK e dava erro 500).
     */
    @PostMapping("/prelecao/{prelecaoId}/excluir")
    public String excluirPrelecao(@PathVariable Long prelecaoId, RedirectAttributes redirectAttributes) {
        if (sessaoRepository.existsByPrelecaoId(prelecaoId)) {
            redirectAttributes.addFlashAttribute("erro", "Esta preleção já tem presenças registradas na sessão e "
                    + "faz parte do histórico: não pode ser excluída. Se ela não vai acontecer, use Cancelar.");
            return "redirect:/prelecao";
        }
        prelecaoRepository.deleteById(prelecaoId);
        redirectAttributes.addFlashAttribute("sucesso", "Preleção excluída.");
        return "redirect:/prelecao";
    }

    // ------------------------------------------------------------------ preletor da preleção

    /** Põe na preleção o preletor escolhido no select: trabalhador ({@code T:}) ou convidado ({@code C:}). */
    void aplicarPreletor(Prelecao prelecao, String escolha) {
        prelecao.setPreletor(null);
        prelecao.setConvidado(null);
        if (!preenchido(escolha)) {
            throw new IllegalArgumentException("Selecione o preletor.");
        }
        Long id = idDaEscolha(escolha);
        if (escolha.startsWith("C:")) {
            Assistido convidado = assistidoRepository.findById(id)
                    .filter(Assistido::isConvidado)
                    .orElseThrow(() -> new IllegalArgumentException("Preletor convidado inválido."));
            prelecao.setConvidado(convidado);
            return;
        }
        Trabalhador preletor = trabalhadorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Preletor inválido: " + id));
        validarPreletor(preletor);
        prelecao.setPreletor(preletor);
    }

    private static Long idDaEscolha(String escolha) {
        if (escolha.length() > 2 && (escolha.startsWith("T:") || escolha.startsWith("C:"))) {
            try {
                return Long.valueOf(escolha.substring(2));
            } catch (NumberFormatException e) {
                // cai no erro abaixo
            }
        }
        throw new IllegalArgumentException("Selecione um preletor válido.");
    }

    private static String escolhaAtual(Prelecao prelecao) {
        if (prelecao.getConvidado() != null) {
            return "C:" + prelecao.getConvidado().getId();
        }
        return prelecao.getPreletor() != null ? "T:" + prelecao.getPreletor().getId() : null;
    }

    private List<Trabalhador> preletoresDisponiveis() {
        return trabalhadorRepository.findAll().stream()
                .filter(trabalhador -> trabalhador.getFuncoes() != null
                        && trabalhador.getFuncoes().contains(TipoTrabalhador.EXPOSITOR_PRELETOR))
                .sorted(Comparator.comparing(t -> t.getAssistido().getNome()))
                .toList();
    }

    /** Convidados ativos e, se a preleção já tem um convidado desativado, ele também (senão sumiria). */
    private List<Assistido> convidadosDisponiveis(Prelecao prelecao) {
        List<Assistido> convidados = new ArrayList<>(
                assistidoRepository.findByVinculoAndAtivoTrueOrderByNomeAsc(Assistido.VINCULO_CONVIDADO));
        Assistido atual = prelecao.getConvidado();
        if (atual != null && atual.getId() != null
                && convidados.stream().noneMatch(c -> c.getId().equals(atual.getId()))) {
            convidados.add(atual);
        }
        return convidados;
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

    /** Uma preleção por data — entre as não canceladas: cancelar libera a data. */
    private void validarUnicidadeDaData(LocalDate data, Long prelecaoIdIgnorada) {
        Optional<Prelecao> existente = prelecaoRepository.findByDataApresentacaoAndCanceladaEmIsNull(data);
        if (existente.isPresent() && !existente.get().getId().equals(prelecaoIdIgnorada)) {
            throw new IllegalArgumentException("Já existe uma preleção cadastrada para esta data. Só pode haver uma por sessão.");
        }
    }

    private static void validarTema(String tema) {
        if (!preenchido(tema)) {
            throw new IllegalArgumentException("Informe o tema da preleção.");
        }
    }

    private static String limitar(String valor, int maximo) {
        return valor.length() > maximo ? valor.substring(0, maximo) : valor;
    }

    private static boolean preenchido(String valor) {
        return valor != null && !valor.isBlank();
    }

    private Prelecao buscar(Long prelecaoId) {
        return prelecaoRepository.findById(prelecaoId)
                .orElseThrow(() -> new IllegalArgumentException("Preleção inválida: " + prelecaoId));
    }

    private void prepararFormulario(Model model, Prelecao prelecao, String preletorEscolhido) {
        model.addAttribute("prelecao", prelecao);
        model.addAttribute("preletores", preletoresDisponiveis());
        model.addAttribute("convidados", convidadosDisponiveis(prelecao));
        model.addAttribute("preletorEscolhido", preletorEscolhido);
    }
}
