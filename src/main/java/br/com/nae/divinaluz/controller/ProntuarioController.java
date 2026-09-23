package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.exception.AvaliacaoPendenteException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Avaliacao;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.Entrevista;
import br.com.nae.divinaluz.model.Evolucao;
import br.com.nae.divinaluz.model.HistoricoDiaFrequencia;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.model.Usuario;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.repository.EntrevistaRepository;
import br.com.nae.divinaluz.repository.HistoricoDiaFrequenciaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import br.com.nae.divinaluz.repository.UsuarioRepository;
import br.com.nae.divinaluz.service.TratamentoService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/")
public class ProntuarioController {

    private final AssistidoRepository assistidoRepository;

    private final SessaoRepository sessaoRepository;

    private final AvaliacaoRepository avaliacaoRepository;

    private final TratamentoService tratamentoService;

    private final TipoTratamentoRepository tipoTratamentoRepository;

    private final TrabalhadorRepository trabalhadorRepository;

    private final HistoricoDiaFrequenciaRepository historicoDiaFrequenciaRepository;

    private final EntrevistaRepository entrevistaRepository;

    private final UsuarioRepository usuarioRepository;

    ProntuarioController(AssistidoRepository assistidoRepository, SessaoRepository sessaoRepository,
            AvaliacaoRepository avaliacaoRepository, TratamentoService tratamentoService,
            TipoTratamentoRepository tipoTratamentoRepository, TrabalhadorRepository trabalhadorRepository,
            HistoricoDiaFrequenciaRepository historicoDiaFrequenciaRepository,
            EntrevistaRepository entrevistaRepository, UsuarioRepository usuarioRepository) {
        this.assistidoRepository = assistidoRepository;
        this.sessaoRepository = sessaoRepository;
        this.avaliacaoRepository = avaliacaoRepository;
        this.tratamentoService = tratamentoService;
        this.tipoTratamentoRepository = tipoTratamentoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
        this.historicoDiaFrequenciaRepository = historicoDiaFrequenciaRepository;
        this.entrevistaRepository = entrevistaRepository;
        this.usuarioRepository = usuarioRepository;
    }

    @GetMapping
    public String index(@RequestParam(required = false, defaultValue = "false") boolean mostrarInativos, Model model) {
        List<Assistido> assistidos = mostrarInativos ? assistidoRepository.findAll() : assistidoRepository.findByAtivo(true);

        // Checklist de frequência do mês corrente (item 4): para cada assistido com dia de
        // assistência definido, calcula as datas esperadas no mês (todas as terças ou domingos,
        // conforme o caso) e marca quais delas já têm sessão registrada.
        LocalDate hoje = LocalDate.now();
        LocalDate inicioMes = hoje.withDayOfMonth(1);
        LocalDate fimMes = hoje.withDayOfMonth(hoje.lengthOfMonth());

        Map<Long, List<LocalDate>> diasEsperadosMes = new LinkedHashMap<>();
        Map<Long, Set<LocalDate>> diasPresentesMes = new LinkedHashMap<>();

        for (Assistido a : assistidos) {
            if (a.getDiaFrequencia() == null) {
                continue;
            }
            List<LocalDate> esperados = new ArrayList<>();
            LocalDate data = inicioMes.with(TemporalAdjusters.nextOrSame(a.getDiaFrequencia().getDiaSemana()));
            while (!data.isAfter(fimMes)) {
                esperados.add(data);
                data = data.plusWeeks(1);
            }
            diasEsperadosMes.put(a.getId(), esperados);

            Set<LocalDate> presentes = sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(a.getId()).stream()
                    .map(sessao -> sessao.getDataConsulta())
                    .filter(d -> !d.isBefore(inicioMes) && !d.isAfter(fimMes))
                    .collect(Collectors.toSet());
            diasPresentesMes.put(a.getId(), presentes);
        }

        model.addAttribute("assistidos", assistidos);
        model.addAttribute("diasEsperadosMes", diasEsperadosMes);
        model.addAttribute("diasPresentesMes", diasPresentesMes);
        model.addAttribute("mostrarInativos", mostrarInativos);
        return "index";
    }

    @GetMapping("/novo")
    public String novo(Model model) {
        model.addAttribute("assistido", new Assistido());
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());
        model.addAttribute("funcoes", TipoTrabalhador.values());
        model.addAttribute("funcoesSelecionadas", Set.of());
        model.addAttribute("modoEdicao", false);
        return "form";
    }

    // Item 4: se um tratamento for escolhido já no cadastro, a data da 1ª sessão passa a ser
    // obrigatória (ver TratamentoService.definirTratamento). O assistido é salvo primeiro (sem
    // tratamento) para existir um ID antes de aplicar a regra e, se necessário, criar o Perfil de
    // Trabalhador (item 2).
    @PostMapping("/salvar")
    public String salvar(@ModelAttribute Assistido assistido, @RequestParam(required = false) List<TipoTrabalhador> funcoes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataPrimeiraSessao,
            RedirectAttributes redirectAttributes) {
        if (dataPrimeiraSessao == null || !ehDiaDeAssistencia(dataPrimeiraSessao)) {
            redirectAttributes.addFlashAttribute("erro", "Informe uma data de assistência que seja Domingo ou Terça-feira.");
            return "redirect:/novo";
        }

        TipoTratamento tratamentoInicial = tipoTratamentoRepository.findByCodigo("P2")
                .orElseThrow(() -> new IllegalStateException("Tratamento padrão P2 não encontrado no catálogo."));
        assistido.setTratamentoAtual(null);
        assistido.setDiaFrequencia(diaFrequenciaDaData(dataPrimeiraSessao));
        atualizarResidenciaLegada(assistido);
        assistidoRepository.save(assistido);

        if ("TRABALHADOR".equals(assistido.getVinculo())) {
            atualizarFuncoesTrabalhador(assistido, funcoes);
        }

        try {
            tratamentoService.iniciarTratamentoInicial(assistido, tratamentoInicial, dataPrimeiraSessao);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/prontuario/" + assistido.getId() + "/editar";
        }

        return "redirect:/";
    }

    private boolean ehDiaDeAssistencia(LocalDate data) {
        return data.getDayOfWeek() == DayOfWeek.TUESDAY || data.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private DiaFrequencia diaFrequenciaDaData(LocalDate data) {
        return data.getDayOfWeek() == DayOfWeek.TUESDAY ? DiaFrequencia.TERCA_19H : DiaFrequencia.DOMINGO_08H;
    }

    @GetMapping("/prontuario/{id}/editar")
    public String editarAssistido(@PathVariable Long id, Model model) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));

        model.addAttribute("assistido", assistido);
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());
        model.addAttribute("funcoes", TipoTrabalhador.values());
        model.addAttribute("funcoesSelecionadas",
            trabalhadorRepository.findByAssistidoId(id).map(trabalhador -> trabalhador.getFuncoes()).orElse(Set.of()));
        model.addAttribute("modoEdicao", true);
        return "form";
    }

    // "dadosForm" chega sem id (o form de edição não envia esse campo), então não há risco de
    // colisão com o path variable — mas buscamos e atualizamos a entidade gerenciada em vez de
    // salvar "dadosForm" diretamente, para não sobrescrever com null campos que não estão no
    // formulário (ex.: cicloIniciadoEm, diaFrequencia, ativo). O tratamento passa pela mesma regra
    // do item 4: só exige data da 1ª sessão se o tratamento realmente mudou.
    @PostMapping("/prontuario/{assistidoId}/editar")
    public String salvarEdicaoAssistido(@PathVariable Long assistidoId, @ModelAttribute Assistido dadosForm,
            @RequestParam(required = false) List<TipoTrabalhador> funcoes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataPrimeiraSessao,
            RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));

        assistido.setNome(dadosForm.getNome());
        assistido.setResidencia(dadosForm.getResidencia());
        assistido.setCep(dadosForm.getCep());
        assistido.setEndereco(dadosForm.getEndereco());
        assistido.setNumero(dadosForm.getNumero());
        assistido.setComplemento(dadosForm.getComplemento());
        assistido.setBairro(dadosForm.getBairro());
        assistido.setCidade(dadosForm.getCidade());
        assistido.setUf(dadosForm.getUf());
        atualizarResidenciaLegada(assistido);
        assistido.setDataNascimento(dadosForm.getDataNascimento());
        assistido.setEstadoCivil(dadosForm.getEstadoCivil());
        assistido.setSexo(dadosForm.getSexo());
        assistido.setEmail(dadosForm.getEmail());
        assistido.setVinculo(dadosForm.getVinculo());
        assistidoRepository.save(assistido);

        if ("TRABALHADOR".equals(assistido.getVinculo())) {
            atualizarFuncoesTrabalhador(assistido, funcoes);
        }

        try {
            tratamentoService.definirTratamento(assistido, dadosForm.getTratamentoAtual(), dataPrimeiraSessao);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/prontuario/" + assistidoId + "/editar";
        }

        return "redirect:/prontuario/" + assistidoId;
    }

    private void atualizarResidenciaLegada(Assistido assistido) {
        List<String> partes = new ArrayList<>();
        if (assistido.getEndereco() != null && !assistido.getEndereco().isBlank()) partes.add(assistido.getEndereco());
        if (assistido.getNumero() != null && !assistido.getNumero().isBlank()) partes.add("n. " + assistido.getNumero());
        if (assistido.getComplemento() != null && !assistido.getComplemento().isBlank()) partes.add(assistido.getComplemento());
        if (assistido.getBairro() != null && !assistido.getBairro().isBlank()) partes.add(assistido.getBairro());
        if (assistido.getCidade() != null && !assistido.getCidade().isBlank()) {
            partes.add(assistido.getUf() != null && !assistido.getUf().isBlank()
                    ? assistido.getCidade() + "/" + assistido.getUf().toUpperCase()
                    : assistido.getCidade());
        }
        assistido.setResidencia(String.join(", ", partes));
    }

    // Exclusão lógica (item 1): nunca apaga o prontuário, só marca como inativo — o assistido
    // pode retomar tratamento futuramente e todo o histórico continua intacto.
    @PostMapping("/prontuario/{assistidoId}/desativar")
    public String desativarAssistido(@PathVariable Long assistidoId) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
        assistido.setAtivo(false);
        assistidoRepository.save(assistido);
        return "redirect:/prontuario/" + assistidoId;
    }

    @PostMapping("/prontuario/{assistidoId}/reativar")
    public String reativarAssistido(@PathVariable Long assistidoId) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
        assistido.setAtivo(true);
        assistidoRepository.save(assistido);
        return "redirect:/prontuario/" + assistidoId;
    }

    // Item 2: reaproveitado pelo cadastro/edição (quando Vínculo = TRABALHADOR) e pela tela
    // dedicada de Perfil de Trabalhador. "funcoes == null" vira conjunto vazio (limpa o perfil),
    // igual ao comportamento já existente da tela dedicada.
    private void atualizarFuncoesTrabalhador(Assistido assistido, List<TipoTrabalhador> funcoes) {
        Trabalhador trabalhador = trabalhadorRepository.findByAssistidoId(assistido.getId())
                .orElseGet(() -> {
                    Trabalhador novo = new Trabalhador();
                    novo.setAssistido(assistido);
                    return novo;
                });
        trabalhador.setFuncoes(funcoes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(funcoes));
        trabalhadorRepository.save(trabalhador);
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
        List<Entrevista> entrevistas = entrevistaRepository.findByAssistidoIdOrderByDataDesc(id);

        // Data da última entrevista e previsão da próxima (4 semanas depois). É apenas
        // informativo — não interfere na regra de bloqueio por 4 sessões já existente.
        LocalDate ultimaEntrevista = entrevistas.isEmpty() ? null : entrevistas.get(0).getData();
        LocalDate proximaEntrevistaPrevista = ultimaEntrevista != null ? ultimaEntrevista.plusWeeks(4) : null;

        model.addAttribute("assistido", assistido);
        model.addAttribute("sessoes", sessoes);
        model.addAttribute("avaliacoes", avaliacoes);
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());
        model.addAttribute("trabalhador", trabalhadorRepository.findByAssistidoId(id).orElse(null));
        model.addAttribute("ultimaEntrevista", ultimaEntrevista);
        model.addAttribute("proximaEntrevistaPrevista", proximaEntrevistaPrevista);
        model.addAttribute("diasFrequencia", DiaFrequencia.values());
        model.addAttribute("historicoDiaFrequencia", historicoDiaFrequenciaRepository.findByAssistidoIdOrderByDataHoraDesc(id));

        return "prontuario"; // Nome do novo arquivo HTML
    }

    @GetMapping("/prontuario/{id}/cartao")
    public String verCartao(@PathVariable Long id, Model model,
            @AuthenticationPrincipal UserDetails usuarioLogado) {
        Usuario usuario = usuarioRepository.findByLoginAndAtivoTrue(usuarioLogado.getUsername())
                .orElseThrow(() -> new AccessDeniedException("Usuário não encontrado."));
        if (usuario.getPerfil().name().equals("ASSISTIDO")
                && (usuario.getAssistido() == null || !id.equals(usuario.getAssistido().getId()))) {
            throw new AccessDeniedException("O assistido só pode consultar o próprio cartão.");
        }
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));

        model.addAttribute("assistido", assistido);
        model.addAttribute("sessoes", sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(id));
        model.addAttribute("statusCartao", assistido.getStatusCartao());
        return "cartao";
    }

    // Alteração do dia de assistência sempre passa por aqui (nunca pelo form geral de
    // cadastro/edição), para garantir que toda troca fique registrada no histórico (item 3).
    @PostMapping("/prontuario/{assistidoId}/dia-frequencia")
    public String alterarDiaFrequencia(@PathVariable Long assistidoId,
            @RequestParam(required = false) DiaFrequencia diaFrequencia,
            @RequestParam(required = false) String motivo) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));

        DiaFrequencia anterior = assistido.getDiaFrequencia();
        if (!Objects.equals(anterior, diaFrequencia)) {
            HistoricoDiaFrequencia log = new HistoricoDiaFrequencia();
            log.setAssistido(assistido);
            log.setDiaAnterior(anterior);
            log.setDiaNovo(diaFrequencia);
            log.setDataHora(LocalDateTime.now());
            log.setMotivo(motivo);
            historicoDiaFrequenciaRepository.save(log);

            assistido.setDiaFrequencia(diaFrequencia);
            assistidoRepository.save(assistido);
        }

        return "redirect:/prontuario/" + assistidoId;
    }

    // Item 4: mudar o tratamento por aqui também exige a data da 1ª sessão do novo ciclo quando o
    // valor realmente muda (ver TratamentoService.definirTratamento).
    @PostMapping("/prontuario/{id}/tratamento")
    public String atualizarTratamento(@PathVariable Long id, @RequestParam(required = false) Long tratamentoAtualId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataPrimeiraSessao,
            RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));

        TipoTratamento tratamento = tratamentoAtualId != null
                ? tipoTratamentoRepository.findById(tratamentoAtualId).orElse(null)
                : null;

        try {
            tratamentoService.definirTratamento(assistido, tratamento, dataPrimeiraSessao);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
        }

        return "redirect:/prontuario/" + id;
    }

    @GetMapping("/prontuario/{id}/trabalhador")
    public String cadastroTrabalhador(@PathVariable Long id, Model model) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));

        Trabalhador trabalhador = trabalhadorRepository.findByAssistidoId(id).orElseGet(Trabalhador::new);

        model.addAttribute("assistido", assistido);
        model.addAttribute("trabalhador", trabalhador);
        model.addAttribute("funcoes", TipoTrabalhador.values());
        return "trabalhador-form";
    }

    // Mesmo cuidado dos outros endpoints de POST com @PathVariable + entidade: aqui optamos
    // por @RequestParam em vez de @ModelAttribute Trabalhador, então nem existe o risco de
    // colisão do "id" do path com o "id" da entidade — mas mantém-se o nome "assistidoId" por
    // consistência com o restante do controller.
    @PostMapping("/prontuario/{assistidoId}/trabalhador")
    public String salvarTrabalhador(@PathVariable Long assistidoId,
            @RequestParam(required = false) List<TipoTrabalhador> funcoes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));

        atualizarFuncoesTrabalhador(assistido, funcoes);

        return "redirect:/prontuario/" + assistidoId;
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
            if (resultado.ouvinte()) {
                redirectAttributes.addFlashAttribute("aviso",
                        "O assistido já teve presença nesta semana. Esta chegada foi registrada como ouvinte e não contou para o cartão.");
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

        model.addAttribute("assistido", assistido);
        model.addAttribute("avaliacao", new Avaliacao());
        model.addAttribute("evolucoes", Evolucao.values());
        return "avaliacao-form";
    }

    // Mesmo cuidado do endpoint de sessão: path variable não pode se chamar "id" porque
    // Avaliacao também tem um campo "id". A Avaliação só registra o diagnóstico (histórico,
    // observações, evolução) — quem decide/comunica o tratamento é a Entrevista (ver abaixo).
    @PostMapping("/prontuario/{assistidoId}/avaliacao")
    public String salvarAvaliacao(@PathVariable Long assistidoId, @ModelAttribute Avaliacao avaliacao) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
        avaliacao.setAssistido(assistido);

        tratamentoService.registrarAvaliacao(avaliacao);

        return "redirect:/prontuario/" + assistidoId;
    }

    // Item 3/1: a Entrevista segue uma Avaliação específica (por isso o avaliacaoId na URL) e sua
    // data precisa cair no dia de assistência do assistido — diferente da Avaliação, cuja data é
    // livre.
    @GetMapping("/prontuario/{id}/nova-entrevista")
    public String novaEntrevista(@PathVariable Long id, @RequestParam Long avaliacaoId, Model model) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));
        Avaliacao avaliacao = avaliacaoRepository.findById(avaliacaoId)
                .orElseThrow(() -> new IllegalArgumentException("Avaliação inválida: " + avaliacaoId));

        Entrevista entrevista = new Entrevista();
        entrevista.setTratamentoIndicado(assistido.getTratamentoAtual());

        model.addAttribute("assistido", assistido);
        model.addAttribute("avaliacao", avaliacao);
        model.addAttribute("entrevista", entrevista);
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());
        return "entrevista-form";
    }

    @PostMapping("/prontuario/{assistidoId}/entrevista")
    public String salvarEntrevista(@PathVariable Long assistidoId, @RequestParam Long avaliacaoId,
            @ModelAttribute Entrevista entrevista, RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
        Avaliacao avaliacao = avaliacaoRepository.findById(avaliacaoId)
                .orElseThrow(() -> new IllegalArgumentException("Avaliação inválida: " + avaliacaoId));

        entrevista.setAssistido(assistido);
        entrevista.setAvaliacao(avaliacao);

        try {
            tratamentoService.registrarEntrevista(entrevista);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/prontuario/" + assistidoId + "/nova-entrevista?avaliacaoId=" + avaliacaoId;
        }

        return "redirect:/prontuario/" + assistidoId;
    }
}