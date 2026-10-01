package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.exception.AvaliacaoPendenteException;
import br.com.nae.divinaluz.exception.CartaoExpiradoException;
import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Avaliacao;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.DiaFrequencia;
import br.com.nae.divinaluz.model.Entrevista;
import br.com.nae.divinaluz.model.Evolucao;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.Permissao;
import br.com.nae.divinaluz.model.SessaoTratamento;
import br.com.nae.divinaluz.model.TipoTratamento;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import br.com.nae.divinaluz.repository.CartaoEncerradoRepository;
import br.com.nae.divinaluz.repository.EntrevistaRepository;
import br.com.nae.divinaluz.repository.HistoricoDiaFrequenciaRepository;
import br.com.nae.divinaluz.repository.SessaoRepository;
import br.com.nae.divinaluz.repository.TipoTratamentoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import br.com.nae.divinaluz.service.AcessoService;
import br.com.nae.divinaluz.service.CheckinService;
import br.com.nae.divinaluz.service.QrCodeService;
import br.com.nae.divinaluz.service.TrabalhadorService;
import br.com.nae.divinaluz.service.TratamentoService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/")
public class ProntuarioController {

    // Marcações do cartão virtual — mesmo bloco de 4 sessões da regra de avaliação
    // (TratamentoService.SESSOES_POR_AVALIACAO).
    private static final int SESSOES_POR_CARTAO = 4;

    private final AssistidoRepository assistidoRepository;

    private final SessaoRepository sessaoRepository;

    private final AvaliacaoRepository avaliacaoRepository;

    private final TratamentoService tratamentoService;

    private final TipoTratamentoRepository tipoTratamentoRepository;

    private final TrabalhadorRepository trabalhadorRepository;

    private final HistoricoDiaFrequenciaRepository historicoDiaFrequenciaRepository;

    private final EntrevistaRepository entrevistaRepository;

    private final CheckinService checkinService;

    private final QrCodeService qrCodeService;

    private final PasswordEncoder passwordEncoder;

    private final AcessoService acessoService;

    private final CartaoEncerradoRepository cartaoEncerradoRepository;

    ProntuarioController(AssistidoRepository assistidoRepository, SessaoRepository sessaoRepository,
            AvaliacaoRepository avaliacaoRepository, TratamentoService tratamentoService,
            TipoTratamentoRepository tipoTratamentoRepository, TrabalhadorRepository trabalhadorRepository,
            HistoricoDiaFrequenciaRepository historicoDiaFrequenciaRepository,
            EntrevistaRepository entrevistaRepository,
            CheckinService checkinService, QrCodeService qrCodeService,
            PasswordEncoder passwordEncoder, AcessoService acessoService,
            CartaoEncerradoRepository cartaoEncerradoRepository) {
        this.assistidoRepository = assistidoRepository;
        this.sessaoRepository = sessaoRepository;
        this.avaliacaoRepository = avaliacaoRepository;
        this.tratamentoService = tratamentoService;
        this.tipoTratamentoRepository = tipoTratamentoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
        this.historicoDiaFrequenciaRepository = historicoDiaFrequenciaRepository;
        this.entrevistaRepository = entrevistaRepository;
        this.checkinService = checkinService;
        this.qrCodeService = qrCodeService;
        this.passwordEncoder = passwordEncoder;
        this.acessoService = acessoService;
        this.cartaoEncerradoRepository = cartaoEncerradoRepository;
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
        model.addAttribute("modoEdicao", false);
        return "form";
    }

    // Item 4: se um tratamento for escolhido já no cadastro, a data da 1ª sessão passa a ser
    // obrigatória (ver TratamentoService.definirTratamento). O assistido é salvo primeiro (sem
    // tratamento) para existir um ID antes de aplicar a regra e, se necessário, criar o Perfil de
    // Trabalhador (item 2).
    @PostMapping("/salvar")
    public String salvar(@ModelAttribute Assistido assistido,
            @RequestParam(required = false) @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate dataPrimeiraSessao,
            @RequestParam(required = false) String senhaAcesso, @RequestParam(required = false) String confirmacaoSenhaAcesso,
            RedirectAttributes redirectAttributes) {
        if (dataPrimeiraSessao == null || !ehDiaDeAssistencia(dataPrimeiraSessao)) {
            redirectAttributes.addFlashAttribute("erro", "Informe uma data de assistência que seja Domingo ou Terça-feira.");
            return "redirect:/novo";
        }

        // Cadastro de assistido cria o acesso automaticamente (perfil ASSISTIDO — ver
        // AcessoService): com e-mail, o próprio assistido define a senha por um link enviado por
        // e-mail; sem e-mail, a recepção precisa informar a senha aqui.
        boolean temEmail = assistido.getEmail() != null && !assistido.getEmail().isBlank();
        String senhaLimpa = senhaAcesso == null ? "" : senhaAcesso.trim();
        if (!temEmail) {
            if (senhaLimpa.length() < 6) {
                redirectAttributes.addFlashAttribute("erro",
                        "Informe um e-mail (para o assistido definir a própria senha) ou uma senha de acesso com pelo menos 6 caracteres.");
                return "redirect:/novo";
            }
            if (!senhaLimpa.equals(confirmacaoSenhaAcesso)) {
                redirectAttributes.addFlashAttribute("erro", "A confirmação da senha de acesso não confere.");
                return "redirect:/novo";
            }
        }

        TipoTratamento tratamentoInicial = tipoTratamentoRepository.findByCodigo("P2")
                .orElseThrow(() -> new IllegalStateException("Tratamento padrão P2 não encontrado no catálogo."));
        assistido.setTratamentoAtual(null);
        // Acesso é criado logo abaixo pelo AcessoService (nunca via bind direto do form).
        assistido.setLogin(null);
        assistido.setSenha(null);
        assistido.setPerfilAcesso(null);
        assistido.setDiaFrequencia(diaFrequenciaDaData(dataPrimeiraSessao));
        // Todo cadastro entra como assistido: virar trabalhador é um passo à parte, no módulo
        // "Cadastrar Trabalhador" (/trabalhadores), que é quem atribui os perfis de trabalho.
        assistido.setVinculo(TrabalhadorService.VINCULO_ASSISTIDO);
        atualizarResidenciaLegada(assistido);
        assistidoRepository.save(assistido);

        try {
            tratamentoService.iniciarTratamentoInicial(assistido, tratamentoInicial, dataPrimeiraSessao);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/prontuario/" + assistido.getId() + "/editar";
        }

        AcessoService.ResultadoAcesso resultadoAcesso = acessoService.criarAcessoAutomatico(assistido, senhaLimpa);
        if (resultadoAcesso.aviso() != null) {
            redirectAttributes.addFlashAttribute("aviso", resultadoAcesso.aviso());
        } else if (temEmail) {
            redirectAttributes.addFlashAttribute("sucesso",
                    assistido.getNome() + " cadastrado(a). Um e-mail foi enviado para \"" + assistido.getEmail()
                            + "\" com o link de definição de senha.");
        } else {
            redirectAttributes.addFlashAttribute("sucesso",
                    assistido.getNome() + " cadastrado(a). Login de acesso: \"" + assistido.getLogin() + "\".");
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
            @RequestParam(required = false) @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate dataPrimeiraSessao,
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
        // O vínculo NÃO vem mais do formulário (a categoria Trabalhador saiu daqui e virou o
        // módulo /trabalhadores): preservá-lo é essencial, senão editar os dados de um
        // trabalhador o rebaixaria a assistido sem querer.
        assistidoRepository.save(assistido);

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

    // --- NOVO MÉTODO ADICIONADO AQUI ---
    @GetMapping("/prontuario/{id}")
    public String verProntuario(@PathVariable Long id, Model model,
            @AuthenticationPrincipal UserDetails usuarioLogado) {
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

        // Cartão retido (Aguardando Avaliação/Entrevista): mostra o status e um atalho direto para
        // o Módulo de Entrevista, para quem já está no prontuário não precisar ir até /entrevistas.
        model.addAttribute("statusCartao", assistido.getStatusCartao());
        model.addAttribute("avaliacaoPendenteEntrevista",
                avaliacaoRepository.findFirstByAssistidoIdAndEntrevistaIsNullOrderByDataDesc(id).orElse(null));
        model.addAttribute("proprioRegistro", ehOProprioRegistro(id, usuarioLogado));

        return "prontuario"; // Nome do novo arquivo HTML
    }

    // Dados de acesso (login/senha/perfil) fazem parte do próprio cadastro do assistido — ver
    // Assistido.login. Qualquer staff cria o acesso (sempre como ASSISTIDO); só o Administrador
    // altera um acesso existente ou escolhe outro perfil.
    @GetMapping("/prontuario/{id}/acesso")
    public String editarAcesso(@PathVariable Long id, Authentication authentication, Model model) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));
        boolean admin = ehAdministrador(authentication);
        if (assistido.getLogin() != null && !admin) {
            return "redirect:/prontuario/" + id;
        }

        model.addAttribute("assistido", assistido);
        model.addAttribute("perfis", PerfilAcesso.values());
        model.addAttribute("podeEscolherPerfil", admin);
        return "acesso-form";
    }

    @PostMapping("/prontuario/{assistidoId}/acesso")
    public String salvarAcesso(@PathVariable Long assistidoId, @RequestParam String login,
            @RequestParam(required = false) String senha, @RequestParam(required = false) String confirmacaoSenha,
            @RequestParam(required = false) PerfilAcesso perfil,
            @RequestParam(defaultValue = "false") boolean acessoAtivo,
            Authentication authentication, RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));

        boolean admin = ehAdministrador(authentication);
        boolean novo = assistido.getLogin() == null;
        String destinoForm = "redirect:/prontuario/" + assistidoId + "/acesso";

        if (!novo && !admin) {
            redirectAttributes.addFlashAttribute("erro", "Somente o administrador pode alterar um acesso existente.");
            return "redirect:/prontuario/" + assistidoId;
        }
        String loginLimpo = login == null ? "" : login.trim();
        if (loginLimpo.isEmpty()) {
            redirectAttributes.addFlashAttribute("erro", "Informe o login.");
            return destinoForm;
        }
        if (!loginLimpo.equals(assistido.getLogin()) && assistidoRepository.existsByLogin(loginLimpo)) {
            redirectAttributes.addFlashAttribute("erro", "Já existe um usuário com este login.");
            return destinoForm;
        }
        boolean trocaSenha = senha != null && !senha.isBlank();
        if (novo || trocaSenha) {
            if (!trocaSenha || senha.length() < 6) {
                redirectAttributes.addFlashAttribute("erro", "Informe uma senha com pelo menos 6 caracteres.");
                return destinoForm;
            }
            if (!senha.equals(confirmacaoSenha)) {
                redirectAttributes.addFlashAttribute("erro", "A confirmação da senha não confere.");
                return destinoForm;
            }
            assistido.setSenha(passwordEncoder.encode(senha));
        }

        assistido.setLogin(loginLimpo);
        if (admin && perfil != null) {
            assistido.setPerfilAcesso(perfil);
        } else if (novo) {
            assistido.setPerfilAcesso(PerfilAcesso.ASSISTIDO);
        }
        assistido.setAcessoAtivo(novo || acessoAtivo);
        assistidoRepository.save(assistido);

        redirectAttributes.addFlashAttribute("sucesso",
                (novo ? "Acesso criado" : "Acesso atualizado") + ". Login: \"" + loginLimpo + "\".");

        // Quem define o que o trabalhador alcança são as funções dele (ver TipoTrabalhador), não o
        // perfil de acesso: um login TRABALHADOR sem nenhuma função entra e não enxerga nada além
        // do próprio cartão. Avisa em vez de bloquear — a função pode ser atribuída depois.
        if (assistido.getPerfilAcesso() == PerfilAcesso.TRABALHADOR
                && trabalhadorRepository.findByAssistidoId(assistidoId)
                        .map(t -> t.getFuncoes().isEmpty()).orElse(true)) {
            redirectAttributes.addFlashAttribute("aviso",
                    "Este acesso é de trabalhador, mas o assistido não tem nenhuma função de trabalho:"
                            + " ele só vai enxergar o próprio cartão. Defina as funções em \"Cadastrar Trabalhador\".");
        }
        return "redirect:/prontuario/" + assistidoId;
    }

    private boolean ehAdministrador(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMINISTRADOR"));
    }

    // PNG do QR code do cartão: o assistido abre no celular e a recepção escaneia. Rota separada do
    // /prontuario/** de staff (ver SecurityConfig) porque o próprio assistido precisa carregá-la.
    @GetMapping(value = "/prontuario/{id}/cartao/qrcode.png", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public byte[] qrCodeDoCartao(@PathVariable Long id, @AuthenticationPrincipal UserDetails usuarioLogado) {
        Assistido assistido = exigirAcessoAoCartao(id, assistidoLogado(usuarioLogado), usuarioLogado);
        String codigo = checkinService.garantirCodigoCartao(assistido);
        String urlCheckin = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/checkin/{codigo}")
                .buildAndExpand(codigo)
                .toUriString();
        return qrCodeService.png(urlCheckin, 320);
    }

    private Assistido assistidoLogado(UserDetails usuarioLogado) {
        return assistidoRepository.findByLoginAndAcessoAtivoTrue(usuarioLogado.getUsername())
                .orElseThrow(() -> new AccessDeniedException("Usuário não encontrado."));
    }

    /**
     * Item 3 dos perfis: o trabalhador tem os poderes do perfil dele sobre todos, menos sobre si
     * mesmo — o entrevistador não entrevista a si próprio, quem o atende é outro entrevistador.
     * Diferente de {@code exigirAcessoAoCartao}, aqui não é 403: é uma regra de atendimento, então
     * a tela volta ao prontuário com o aviso.
     */
    private boolean ehOProprioRegistro(Long assistidoId, UserDetails usuarioLogado) {
        if (usuarioLogado == null) {
            return false;
        }
        return assistidoRepository.findByLoginAndAcessoAtivoTrue(usuarioLogado.getUsername())
                .map(logado -> assistidoId.equals(logado.getId()))
                .orElse(false);
    }

    private static final String ERRO_ATENDER_A_SI =
            "Ninguém conduz o próprio tratamento: outro trabalhador precisa registrar esta avaliação/entrevista.";

    /**
     * Todo mundo alcança o próprio cartão (e o próprio QR); o cartão de outro exige a permissão de
     * consulta. Fica aqui, e não no {@code SecurityConfig}, porque a rota é a mesma nos dois casos —
     * é a posse que decide.
     */
    private Assistido exigirAcessoAoCartao(Long id, Assistido logado, UserDetails usuarioLogado) {
        boolean podeConsultarDeOutro = usuarioLogado != null && usuarioLogado.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(Permissao.CONSULTA.getAuthority()));
        if (!podeConsultarDeOutro && !id.equals(logado.getId())) {
            throw new AccessDeniedException("Sem permissão para consultar o cartão de outro assistido.");
        }
        return assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));
    }

    @GetMapping("/prontuario/{id}/cartao")
    public String verCartao(@PathVariable Long id, Model model,
            @AuthenticationPrincipal UserDetails usuarioLogado) {
        Assistido logado = assistidoLogado(usuarioLogado);
        Assistido assistido = exigirAcessoAoCartao(id, logado, usuarioLogado);

        // Regra 4 do cartão: enquanto o cartão está retido (Aguardando Avaliação / Aguardando
        // Entrevista) o assistido não tem visibilidade do conteúdo — vê apenas os próprios dados e
        // o status. O staff (recepção/entrevistador) continua com visibilidade total (regra 5) —
        // exceto sobre o PRÓPRIO cartão: diante do próprio tratamento, todo trabalhador é um
        // assistido como qualquer outro (ninguém conduz o próprio tratamento).
        CartaoStatus status = assistido.getStatusCartao();
        boolean proprioCartao = id.equals(logado.getId());
        boolean cartaoRetido = (logado.getPerfilAcesso() == PerfilAcesso.ASSISTIDO || proprioCartao)
                && (status == CartaoStatus.AGUARDANDO_AVALIACAO || status == CartaoStatus.AGUARDANDO_ENTREVISTA);

        List<SessaoTratamento> sessoes = cartaoRetido
                ? List.of()
                : sessaoRepository.findByAssistidoIdOrderByDataConsultaDesc(id);

        // Presenças efetivas do ciclo atual: são elas que preenchem as 4 marcações do cartão
        // virtual (o mesmo bloco de 4 sessões que o TratamentoService conta para a avaliação).
        LocalDate cicloIniciadoEm = assistido.getCicloIniciadoEm();
        List<SessaoTratamento> presencasDoCiclo = sessoes.stream()
                .filter(sessao -> !sessao.isOuvinte() && sessao.getDataConsulta() != null)
                .filter(sessao -> cicloIniciadoEm == null || !sessao.getDataConsulta().isBefore(cicloIniciadoEm))
                .sorted(Comparator.comparing(SessaoTratamento::getDataConsulta))
                .limit(SESSOES_POR_CARTAO)
                .toList();

        // Lista (possivelmente vazia) só para o template desenhar as marcações que faltam — com um
        // contador simples, #numbers.sequence(1,0) devolveria [1,0] e desenharia um slot a mais.
        List<Integer> marcacoesVazias = new ArrayList<>();
        for (int i = presencasDoCiclo.size() + 1; i <= SESSOES_POR_CARTAO; i++) {
            marcacoesVazias.add(i);
        }

        // Código do QR que a recepção escaneia. Fica visível mesmo com o cartão retido: é como a
        // recepção identifica a pessoa para encaminhá-la à avaliação/entrevista (regra 5).
        checkinService.garantirCodigoCartao(assistido);

        model.addAttribute("assistido", assistido);
        model.addAttribute("sessoes", sessoes);
        model.addAttribute("statusCartao", status);
        model.addAttribute("cartaoRetido", cartaoRetido);
        // Cartões de ciclos já encerrados (concluído / incompleto por tempo / interrompido). O
        // assistido enxerga esse histórico mesmo com o cartão atual retido: a regra 4 esconde o
        // cartão *em andamento*, não os tratamentos que já terminaram.
        model.addAttribute("cartoesEncerrados", cartaoEncerradoRepository.findByAssistidoIdOrderByEncerradoEmDesc(id));
        model.addAttribute("sessaoAberta", checkinService.sessaoComCheckinAberto().orElse(null));
        model.addAttribute("presencasDoCiclo", presencasDoCiclo);
        model.addAttribute("marcacoesVazias", marcacoesVazias);
        model.addAttribute("sessoesPorCartao", SESSOES_POR_CARTAO);
        model.addAttribute("totalOuvinte", sessoes.stream().filter(SessaoTratamento::isOuvinte).count());
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

        tratamentoService.alterarDiaFrequencia(assistido, diaFrequencia, motivo);

        return "redirect:/prontuario/" + assistidoId;
    }

    // Item 4: mudar o tratamento por aqui também exige a data da 1ª sessão do novo ciclo quando o
    // valor realmente muda (ver TratamentoService.definirTratamento).
    @PostMapping("/prontuario/{id}/tratamento")
    public String atualizarTratamento(@PathVariable Long id, @RequestParam(required = false) Long tratamentoAtualId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate dataPrimeiraSessao,
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

    // O perfil de trabalho do assistido é editado no módulo "Cadastrar Trabalhador"
    // (TrabalhadorController, /trabalhadores/{id}); o prontuário apenas aponta para lá.
    @GetMapping("/prontuario/{id}/trabalhador")
    public String cadastroTrabalhador(@PathVariable Long id) {
        return "redirect:/trabalhadores/" + id;
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
            @RequestParam(defaultValue = "false") boolean reiniciarP2, RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));
        sessao.setAssistido(assistido);

        try {
            TratamentoService.ResultadoSessao resultado = tratamentoService.registrarSessao(sessao, reiniciarP2);
            if (resultado.tratamentoReiniciado()) {
                redirectAttributes.addFlashAttribute("aviso",
                        "O assistido ficou 3 semanas ou mais sem sessão. O tratamento foi reiniciado em P2. "
                                + "Uma nova entrevista é recomendada (opcional).");
            }
            if (resultado.ouvinte()) {
                redirectAttributes.addFlashAttribute("aviso",
                        "O assistido já teve presença nesta semana. Esta chegada foi registrada como ouvinte e não contou para o cartão.");
            }
        } catch (CartaoExpiradoException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            redirectAttributes.addFlashAttribute("cartaoExpirado", true);
            return "redirect:/prontuario/" + assistidoId + "/nova-sessao";
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
    public String novaAvaliacao(@PathVariable Long id, Model model,
            @AuthenticationPrincipal UserDetails usuarioLogado, RedirectAttributes redirectAttributes) {
        if (ehOProprioRegistro(id, usuarioLogado)) {
            redirectAttributes.addFlashAttribute("erro", ERRO_ATENDER_A_SI);
            return "redirect:/prontuario/" + id;
        }
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
    public String salvarAvaliacao(@PathVariable Long assistidoId, @ModelAttribute Avaliacao avaliacao,
            @AuthenticationPrincipal UserDetails usuarioLogado, RedirectAttributes redirectAttributes) {
        if (ehOProprioRegistro(assistidoId, usuarioLogado)) {
            redirectAttributes.addFlashAttribute("erro", ERRO_ATENDER_A_SI);
            return "redirect:/prontuario/" + assistidoId;
        }
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
    public String novaEntrevista(@PathVariable Long id, @RequestParam Long avaliacaoId, Model model,
            @AuthenticationPrincipal UserDetails usuarioLogado, RedirectAttributes redirectAttributes) {
        if (ehOProprioRegistro(id, usuarioLogado)) {
            redirectAttributes.addFlashAttribute("erro", ERRO_ATENDER_A_SI);
            return "redirect:/prontuario/" + id;
        }
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
            @ModelAttribute Entrevista entrevista, @AuthenticationPrincipal UserDetails usuarioLogado,
            RedirectAttributes redirectAttributes) {
        if (ehOProprioRegistro(assistidoId, usuarioLogado)) {
            redirectAttributes.addFlashAttribute("erro", ERRO_ATENDER_A_SI);
            return "redirect:/prontuario/" + assistidoId;
        }
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