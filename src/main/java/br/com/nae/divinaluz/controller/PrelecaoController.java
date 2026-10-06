package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Prelecao;
import br.com.nae.divinaluz.model.PreletorConvidado;
import br.com.nae.divinaluz.model.SituacaoPrelecao;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.PrelecaoRepository;
import br.com.nae.divinaluz.repository.PreletorConvidadoRepository;
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
 * <p>Desde 2026-10-05 o preletor pode ser um <strong>trabalhador da casa</strong> (com a função
 * Expositor/Preletor) ou um <strong>convidado</strong> ({@link PreletorConvidado}), que não é
 * assistido. No formulário os dois aparecem no mesmo select, com o valor {@code T:<id>} ou
 * {@code C:<id>}, e {@code NOVO} cadastra um convidado ali mesmo. O parâmetro se chama
 * {@code preletorEscolhido}, e não {@code preletor}, porque o {@code @ModelAttribute Prelecao}
 * tentaria converter "T:12" no campo {@code preletor} (um Trabalhador) e a requisição daria 400.
 * A preleção também pode ser <strong>cancelada</strong> (fica na escala com o motivo e libera a
 * data) e reativada.</p>
 */
@Controller
public class PrelecaoController {

    /** Valor do select para cadastrar um convidado novo junto com a preleção. */
    static final String NOVO_CONVIDADO = "NOVO";

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PrelecaoRepository prelecaoRepository;
    private final TrabalhadorRepository trabalhadorRepository;
    private final PreletorConvidadoRepository convidadoRepository;
    private final SessaoRepository sessaoRepository;
    private final CheckinService checkinService;

    public PrelecaoController(PrelecaoRepository prelecaoRepository, TrabalhadorRepository trabalhadorRepository,
            PreletorConvidadoRepository convidadoRepository, SessaoRepository sessaoRepository,
            CheckinService checkinService) {
        this.prelecaoRepository = prelecaoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
        this.convidadoRepository = convidadoRepository;
        this.sessaoRepository = sessaoRepository;
        this.checkinService = checkinService;
    }

    /**
     * Item da escala já classificado para a view (ver {@link SituacaoPrelecao}). A ordenação
     * coloca primeiro o que ainda vai acontecer — esta semana, depois as agendadas —, depois o
     * histórico, da mais recente para a mais antiga, e as canceladas no fim.
     */
    public record ItemEscala(Prelecao prelecao, SituacaoPrelecao situacao) {}

    /** O convidado novo digitado no próprio formulário da preleção. */
    public record NovoConvidado(String nome, String telefone, String origem) {}

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
        prepararFormulario(model, new Prelecao(), null, null);
        return "prelecao-form";
    }

    @GetMapping("/prelecao/{prelecaoId}/editar")
    public String editarPrelecao(@PathVariable Long prelecaoId, Model model) {
        Prelecao prelecao = buscar(prelecaoId);
        prepararFormulario(model, prelecao, escolhaAtual(prelecao), null);
        return "prelecao-form";
    }

    @PostMapping("/prelecao/salvar")
    public String salvarPrelecao(@ModelAttribute Prelecao prelecao,
                                 @RequestParam(required = false) String preletorEscolhido,
                                 @RequestParam(required = false) String novoConvidadoNome,
                                 @RequestParam(required = false) String novoConvidadoTelefone,
                                 @RequestParam(required = false) String novoConvidadoOrigem,
                                 RedirectAttributes redirectAttributes, Model model) {
        NovoConvidado novo = new NovoConvidado(novoConvidadoNome, novoConvidadoTelefone, novoConvidadoOrigem);
        try {
            PreletorConvidado convidadoNovo = aplicarPreletor(prelecao, preletorEscolhido, novo);
            validarDataPrelecao(prelecao.getDataApresentacao());
            validarUnicidadeDaData(prelecao.getDataApresentacao(), null);
            validarTema(prelecao.getTema());

            gravar(prelecao, convidadoNovo);
            return "redirect:/prelecao";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            prelecao.setDataApresentacao(null);
            prepararFormulario(model, prelecao, preletorEscolhido, novo);
            model.addAttribute("erro", e.getMessage());
            return "prelecao-form";
        }
    }

    @PostMapping("/prelecao/{prelecaoId}/editar")
    public String atualizarPrelecao(@PathVariable Long prelecaoId, @ModelAttribute Prelecao dadosPrelecao,
                                    @RequestParam(required = false) String preletorEscolhido,
                                    @RequestParam(required = false) String novoConvidadoNome,
                                    @RequestParam(required = false) String novoConvidadoTelefone,
                                    @RequestParam(required = false) String novoConvidadoOrigem,
                                    RedirectAttributes redirectAttributes, Model model) {
        NovoConvidado novo = new NovoConvidado(novoConvidadoNome, novoConvidadoTelefone, novoConvidadoOrigem);
        try {
            Prelecao prelecao = buscar(prelecaoId);
            prelecao.setDataApresentacao(dadosPrelecao.getDataApresentacao());
            prelecao.setTema(dadosPrelecao.getTema());
            PreletorConvidado convidadoNovo = aplicarPreletor(prelecao, preletorEscolhido, novo);
            validarDataPrelecao(prelecao.getDataApresentacao());
            if (!prelecao.isCancelada()) {
                validarUnicidadeDaData(prelecao.getDataApresentacao(), prelecaoId);
            }
            validarTema(prelecao.getTema());

            gravar(prelecao, convidadoNovo);
            return "redirect:/prelecao";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            dadosPrelecao.setId(prelecaoId);
            dadosPrelecao.setDataApresentacao(null);
            prepararFormulario(model, dadosPrelecao, preletorEscolhido, novo);
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
        prelecao.setMotivoCancelamento(opcional(motivo, 255));
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

    // ------------------------------------------------------------------ preletores convidados

    @GetMapping("/prelecao/convidados")
    public String listarConvidados(Model model) {
        prepararConvidados(model, new PreletorConvidado());
        return "prelecao-convidados";
    }

    @GetMapping("/prelecao/convidados/{convidadoId}")
    public String editarConvidado(@PathVariable Long convidadoId, Model model) {
        prepararConvidados(model, buscarConvidado(convidadoId));
        return "prelecao-convidados";
    }

    @PostMapping("/prelecao/convidados")
    public String salvarConvidado(@ModelAttribute("convidado") PreletorConvidado dados,
                                  RedirectAttributes redirectAttributes, Model model) {
        return gravarConvidado(new PreletorConvidado(), dados, redirectAttributes, model);
    }

    @PostMapping("/prelecao/convidados/{convidadoId}")
    public String atualizarConvidado(@PathVariable Long convidadoId,
                                     @ModelAttribute("convidado") PreletorConvidado dados,
                                     RedirectAttributes redirectAttributes, Model model) {
        return gravarConvidado(buscarConvidado(convidadoId), dados, redirectAttributes, model);
    }

    /** Desativar tira o convidado da lista de escolha da escala, sem mexer nas preleções dele. */
    @PostMapping("/prelecao/convidados/{convidadoId}/ativo")
    public String alternarConvidado(@PathVariable Long convidadoId, RedirectAttributes redirectAttributes) {
        PreletorConvidado convidado = buscarConvidado(convidadoId);
        convidado.setAtivo(!convidado.isAtivo());
        convidadoRepository.save(convidado);
        redirectAttributes.addFlashAttribute("sucesso", convidado.getNome()
                + (convidado.isAtivo() ? " voltou a aparecer na escala." : " não aparece mais para escolher na escala."));
        return "redirect:/prelecao/convidados";
    }

    @PostMapping("/prelecao/convidados/{convidadoId}/excluir")
    public String excluirConvidado(@PathVariable Long convidadoId, RedirectAttributes redirectAttributes) {
        PreletorConvidado convidado = buscarConvidado(convidadoId);
        if (prelecaoRepository.existsByConvidadoId(convidadoId)) {
            redirectAttributes.addFlashAttribute("erro", convidado.getNome() + " já está na escala de preleções e "
                    + "não pode ser excluído(a). Use Desativar para tirá-lo(a) da lista de escolha.");
            return "redirect:/prelecao/convidados";
        }
        convidadoRepository.delete(convidado);
        redirectAttributes.addFlashAttribute("sucesso", convidado.getNome() + " foi excluído(a).");
        return "redirect:/prelecao/convidados";
    }

    private String gravarConvidado(PreletorConvidado convidado, PreletorConvidado dados,
                                   RedirectAttributes redirectAttributes, Model model) {
        try {
            convidado.setNome(exigirNomeConvidado(dados.getNome()));
            convidado.setTelefone(opcional(dados.getTelefone(), 30));
            convidado.setEmail(validarEmail(dados.getEmail()));
            convidado.setOrigem(opcional(dados.getOrigem(), 150));
            convidado.setObservacoes(preenchido(dados.getObservacoes()) ? dados.getObservacoes().trim() : null);
        } catch (IllegalArgumentException e) {
            dados.setId(convidado.getId());
            prepararConvidados(model, dados);
            model.addAttribute("erro", e.getMessage());
            return "prelecao-convidados";
        }
        convidadoRepository.save(convidado);
        redirectAttributes.addFlashAttribute("sucesso", "Convidado(a) " + convidado.getNome() + " salvo(a).");
        return "redirect:/prelecao/convidados";
    }

    private void prepararConvidados(Model model, PreletorConvidado emEdicao) {
        model.addAttribute("convidados", convidadoRepository.findAllByOrderByNomeAsc());
        model.addAttribute("convidado", emEdicao);
    }

    // ------------------------------------------------------------------ preletor da preleção

    /**
     * Põe na preleção o preletor escolhido no select (trabalhador ou convidado). Para {@code NOVO},
     * devolve o convidado ainda não gravado — ele só é gravado junto com a preleção, depois de todas
     * as validações, para um erro de data não deixar um convidado solto.
     */
    PreletorConvidado aplicarPreletor(Prelecao prelecao, String escolha, NovoConvidado novo) {
        prelecao.setPreletor(null);
        prelecao.setConvidado(null);
        if (!preenchido(escolha)) {
            throw new IllegalArgumentException("Selecione o preletor.");
        }
        if (NOVO_CONVIDADO.equals(escolha)) {
            PreletorConvidado convidado = new PreletorConvidado();
            convidado.setNome(exigirNomeConvidado(novo.nome()));
            convidado.setTelefone(opcional(novo.telefone(), 30));
            convidado.setOrigem(opcional(novo.origem(), 150));
            prelecao.setConvidado(convidado);
            return convidado;
        }
        Long id = idDaEscolha(escolha);
        if (escolha.startsWith("C:")) {
            prelecao.setConvidado(buscarConvidado(id));
            return null;
        }
        Trabalhador preletor = trabalhadorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Preletor inválido: " + id));
        validarPreletor(preletor);
        prelecao.setPreletor(preletor);
        return null;
    }

    private void gravar(Prelecao prelecao, PreletorConvidado convidadoNovo) {
        if (convidadoNovo != null) {
            convidadoRepository.save(convidadoNovo);
        }
        prelecaoRepository.save(prelecao);
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
    private List<PreletorConvidado> convidadosDisponiveis(Prelecao prelecao) {
        List<PreletorConvidado> convidados = new ArrayList<>(convidadoRepository.findByAtivoTrueOrderByNomeAsc());
        PreletorConvidado atual = prelecao.getConvidado();
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

    private static String exigirNomeConvidado(String nome) {
        if (!preenchido(nome) || nome.trim().length() < 3) {
            throw new IllegalArgumentException("Informe o nome do preletor convidado.");
        }
        return limitar(nome.trim(), 150);
    }

    private static String validarEmail(String email) {
        if (!preenchido(email)) {
            return null;
        }
        String limpo = email.trim().toLowerCase();
        if (limpo.length() > 150 || !limpo.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("E-mail do convidado inválido.");
        }
        return limpo;
    }

    private static String opcional(String valor, int maximo) {
        return preenchido(valor) ? limitar(valor.trim(), maximo) : null;
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

    private PreletorConvidado buscarConvidado(Long convidadoId) {
        return convidadoRepository.findById(convidadoId)
                .orElseThrow(() -> new IllegalArgumentException("Convidado inválido: " + convidadoId));
    }

    private void prepararFormulario(Model model, Prelecao prelecao, String preletorEscolhido, NovoConvidado novo) {
        model.addAttribute("prelecao", prelecao);
        model.addAttribute("preletores", preletoresDisponiveis());
        model.addAttribute("convidados", convidadosDisponiveis(prelecao));
        model.addAttribute("preletorEscolhido", preletorEscolhido);
        model.addAttribute("novoConvidado", novo != null ? novo : new NovoConvidado(null, null, null));
    }
}
