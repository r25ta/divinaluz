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
import br.com.nae.divinaluz.service.CodigoAcessoService;
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
import org.springframework.dao.DataIntegrityViolationException;
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

    private final AcessoService acessoService;

    private final CartaoEncerradoRepository cartaoEncerradoRepository;

    private final CodigoAcessoService codigoAcessoService;

    ProntuarioController(AssistidoRepository assistidoRepository, SessaoRepository sessaoRepository,
            AvaliacaoRepository avaliacaoRepository, TratamentoService tratamentoService,
            TipoTratamentoRepository tipoTratamentoRepository, TrabalhadorRepository trabalhadorRepository,
            HistoricoDiaFrequenciaRepository historicoDiaFrequenciaRepository,
            EntrevistaRepository entrevistaRepository,
            CheckinService checkinService, QrCodeService qrCodeService,
            AcessoService acessoService,
            CartaoEncerradoRepository cartaoEncerradoRepository,
            CodigoAcessoService codigoAcessoService) {
        this.codigoAcessoService = codigoAcessoService;
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
        this.acessoService = acessoService;
        this.cartaoEncerradoRepository = cartaoEncerradoRepository;
    }

    @GetMapping
    public String index(@RequestParam(required = false, defaultValue = "false") boolean mostrarInativos,
            Authentication authentication, Model model) {
        // Sem CONSULTA (assistido, Expositor/Preletor...) a listagem não é para a pessoa: o
        // endereço de entrada leva ao próprio cartão. A listagem em si continua exigindo CONSULTA.
        boolean podeConsultar = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(Permissao.CONSULTA.getAuthority()));
        if (!podeConsultar) {
            return assistidoRepository.findByLoginAndAcessoAtivoTrue(authentication.getName())
                    .map(logado -> "redirect:/prontuario/" + logado.getId() + "/cartao")
                    .orElseThrow(() -> new AccessDeniedException("Acesso sem cadastro vinculado."));
        }
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
        return exibirCadastro(model, new Assistido(), null, null, null);
    }

    /**
     * Cadastro completo, com o acesso ao sistema na mesma tela (2026-10-03): login sugerido a partir
     * do nome, e-mail único e senha. Tudo é validado ANTES de gravar qualquer coisa, e um erro devolve
     * o formulário preenchido — antes, voltava um {@code /novo} vazio e a recepção digitava tudo de
     * novo.
     */
    @PostMapping("/salvar")
    public String salvar(@ModelAttribute Assistido assistido,
            @RequestParam(required = false) @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate dataPrimeiraSessao,
            @RequestParam(required = false) String login,
            @RequestParam(required = false) String senhaAcesso, @RequestParam(required = false) String confirmacaoSenhaAcesso,
            Model model, RedirectAttributes redirectAttributes) {
        assistido.setEmail(AcessoService.normalizarEmail(assistido.getEmail()));
        String loginLimpo = AcessoService.normalizarLogin(login);
        try {
            if (dataPrimeiraSessao == null || !ehDiaDeAssistencia(dataPrimeiraSessao)) {
                throw new RegraNegocioException("Informe uma data de assistência que seja Domingo ou Terça-feira.");
            }
            acessoService.validarEmail(assistido.getEmail(), null);
            acessoService.validarLogin(loginLimpo, null);
            acessoService.validarSenha(senhaAcesso, confirmacaoSenhaAcesso,
                    acessoService.senhaObrigatoria(assistido.getEmail()),
                    acessoService.motivoSenhaObrigatoria(assistido.getEmail()));
        } catch (RegraNegocioException e) {
            return exibirCadastro(model, assistido, loginLimpo, dataPrimeiraSessao, e.getMessage());
        }

        TipoTratamento tratamentoInicial = tipoTratamentoRepository.findByCodigo("P2")
                .orElseThrow(() -> new IllegalStateException("Tratamento padrão P2 não encontrado no catálogo."));
        assistido.setTratamentoAtual(null);
        // O acesso é gravado logo abaixo pelo AcessoService, nunca pelo bind direto do formulário.
        assistido.setLogin(null);
        assistido.setSenha(null);
        assistido.setPerfilAcesso(null);
        assistido.setDiaFrequencia(diaFrequenciaDaData(dataPrimeiraSessao));
        // Todo cadastro entra como assistido: virar trabalhador é um passo à parte, no módulo
        // "Cadastrar Trabalhador" (/trabalhadores), que é quem atribui os perfis de trabalho.
        assistido.setVinculo(TrabalhadorService.VINCULO_ASSISTIDO);
        atualizarResidenciaLegada(assistido);
        try {
            assistidoRepository.save(assistido);
        } catch (DataIntegrityViolationException e) {
            // Outra recepção gravou o mesmo e-mail entre a validação e aqui (índice da V35).
            assistido.setId(null);
            return exibirCadastro(model, assistido, loginLimpo, dataPrimeiraSessao,
                    "Este e-mail acabou de ser usado em outro cadastro. Confira e tente de novo.");
        }

        String avisoAcesso = null;
        try {
            acessoService.criarAcesso(assistido, loginLimpo, senhaAcesso);
        } catch (DataIntegrityViolationException e) {
            avisoAcesso = "O login \"" + loginLimpo + "\" acabou de ser usado em outro cadastro, então o acesso"
                    + " não foi criado. Crie pela edição do cadastro, com outro login.";
        }

        try {
            tratamentoService.iniciarTratamentoInicial(assistido, tratamentoInicial, dataPrimeiraSessao);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/prontuario/" + assistido.getId() + "/editar";
        }

        if (avisoAcesso != null) {
            redirectAttributes.addFlashAttribute("aviso", avisoAcesso);
        } else {
            boolean entraPorCodigo = assistido.getSenha() == null;
            redirectAttributes.addFlashAttribute("sucesso", assistido.getNome() + " cadastrado(a). Login de acesso: \""
                    + assistido.getLogin() + "\"" + (entraPorCodigo
                            ? " — entra pelo código enviado a " + assistido.getEmail() + "."
                            : "."));
        }
        return "redirect:/";
    }

    private String exibirCadastro(Model model, Assistido assistido, String loginInformado,
            LocalDate dataPrimeiraSessao, String erro) {
        model.addAttribute("assistido", assistido);
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());
        model.addAttribute("modoEdicao", false);
        model.addAttribute("loginInformado", loginInformado);
        model.addAttribute("dataPrimeiraSessaoInformada", dataPrimeiraSessao);
        model.addAttribute("emailHabilitado", acessoService.envioDeEmailLigado());
        model.addAttribute("senhaMinima", AcessoService.SENHA_MINIMA);
        model.addAttribute("erro", erro);
        return "form";
    }

    private boolean ehDiaDeAssistencia(LocalDate data) {
        return data.getDayOfWeek() == DayOfWeek.TUESDAY || data.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private DiaFrequencia diaFrequenciaDaData(LocalDate data) {
        return data.getDayOfWeek() == DayOfWeek.TUESDAY ? DiaFrequencia.TERCA_19H : DiaFrequencia.DOMINGO_08H;
    }

    @GetMapping("/prontuario/{id}/editar")
    public String editarAssistido(@PathVariable Long id, Authentication authentication, Model model) {
        Assistido assistido = assistidoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + id));
        return exibirEdicao(model, assistido, null, false, authentication, null);
    }

    private String exibirEdicao(Model model, Assistido assistido, String loginInformado, boolean criarAcessoMarcado,
            Authentication authentication, String erro) {
        model.addAttribute("assistido", assistido);
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());
        model.addAttribute("modoEdicao", true);
        model.addAttribute("loginInformado", loginInformado);
        model.addAttribute("criarAcessoMarcado", criarAcessoMarcado);
        model.addAttribute("podeEditarAcesso", podeEditarAcessoDe(authentication, assistido));
        model.addAttribute("podeEscolherPerfil", podeEscolherPerfil(authentication));
        model.addAttribute("perfis", perfisQuePodeConceder(authentication));
        model.addAttribute("emailHabilitado", acessoService.envioDeEmailLigado());
        model.addAttribute("senhaMinima", AcessoService.SENHA_MINIMA);
        if (erro != null) {
            model.addAttribute("erro", erro);
        }
        return "form";
    }

    /**
     * Antes, o acesso tinha tela própria ({@code /prontuario/{id}/acesso}); desde 2026-10-03 ele é a
     * seção "Acesso ao Sistema" da edição do cadastro. A rota antiga leva para lá.
     */
    @GetMapping("/prontuario/{id}/acesso")
    public String acessoRedireciona(@PathVariable Long id) {
        return "redirect:/prontuario/" + id + "/editar#acesso";
    }

    // "dadosForm" chega sem id (o form de edição não envia esse campo), então não há risco de
    // colisão com o path variable — mas buscamos e atualizamos a entidade gerenciada em vez de
    // salvar "dadosForm" diretamente, para não sobrescrever com null campos que não estão no
    // formulário (ex.: cicloIniciadoEm, diaFrequencia, ativo). O tratamento passa pela mesma regra
    // do item 4: só exige data da 1ª sessão se o tratamento realmente mudou.
    //
    // Acesso ao sistema (2026-10-03), na mesma submissão:
    //  - quem ainda não tem login ganha um marcando "Criar acesso" (qualquer um com CADASTRO);
    //  - quem já tem só tem o acesso alterado por quem pode (podeEditarAcessoDe — desde 2026-10-04
    //    a Recepcionista edita o login de assistidos), e só quando a seção veio no formulário
    //    (alterarAcesso) — um POST só com os dados pessoais nunca mexe no acesso.
    // O tratamento só é tocado por quem tem PRONTUARIO (2026-10-04): para os demais o select nem
    // aparece, e chamar definirTratamento com ele ausente apagaria o tratamento atual.
    // Tudo é validado antes de gravar; um erro devolve o formulário preenchido.
    @PostMapping("/prontuario/{assistidoId}/editar")
    public String salvarEdicaoAssistido(@PathVariable Long assistidoId, @ModelAttribute Assistido dadosForm,
            @RequestParam(required = false) @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate dataPrimeiraSessao,
            @RequestParam(defaultValue = "false") boolean criarAcesso,
            @RequestParam(defaultValue = "false") boolean alterarAcesso,
            @RequestParam(required = false) String login,
            @RequestParam(required = false) String senha, @RequestParam(required = false) String confirmacaoSenha,
            @RequestParam(required = false) PerfilAcesso perfil,
            @RequestParam(defaultValue = "false") boolean acessoAtivo,
            Authentication authentication, Model model, RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));

        boolean temAcesso = assistido.getLogin() != null;
        boolean vaiCriarAcesso = !temAcesso && criarAcesso;
        boolean vaiAlterarAcesso = temAcesso && alterarAcesso && podeEditarAcessoDe(authentication, assistido);
        // Perfil só de quem pode escolher, e ADMINISTRADOR só concedido por um Administrador.
        PerfilAcesso perfilPedido = perfil != null && perfisQuePodeConceder(authentication).contains(perfil) ? perfil : null;
        boolean proprioAcesso = temAcesso && assistido.getLogin().equals(authentication.getName());
        String email = AcessoService.normalizarEmail(dadosForm.getEmail());
        String loginLimpo = AcessoService.normalizarLogin(login);

        try {
            acessoService.validarEmail(email, assistidoId);
            if (vaiCriarAcesso) {
                acessoService.validarLogin(loginLimpo, assistidoId);
                acessoService.validarSenha(senha, confirmacaoSenha, acessoService.senhaObrigatoria(email),
                        acessoService.motivoSenhaObrigatoria(email));
            } else if (vaiAlterarAcesso) {
                acessoService.validarLogin(loginLimpo, assistidoId);
                // Sem senha gravada, quem entra por código precisa continuar tendo como entrar.
                boolean semOutraEntrada = assistido.getSenha() == null && acessoService.senhaObrigatoria(email);
                acessoService.validarSenha(senha, confirmacaoSenha, semOutraEntrada,
                        acessoService.motivoSenhaObrigatoria(email));
                if (proprioAcesso && !loginLimpo.equals(assistido.getLogin())) {
                    throw new RegraNegocioException("Você não pode trocar o seu próprio login: a sua sessão "
                            + "ficaria presa ao login antigo. Peça a outra pessoa com essa permissão.");
                }
                if (proprioAcesso && (!acessoAtivo || (perfilPedido != null && perfilPedido != assistido.getPerfilAcesso()))) {
                    throw new RegraNegocioException("Você não pode desativar o seu próprio acesso nem trocar o seu "
                            + "próprio perfil — ficaria sem como desfazer. Peça a outra pessoa com essa permissão.");
                }
            }
        } catch (RegraNegocioException e) {
            // Reexibe o que foi digitado, mas o estado do acesso vem do banco (é ele que decide qual
            // versão da seção "Acesso ao Sistema" aparece).
            dadosForm.setId(assistidoId);
            dadosForm.setEmail(email);
            dadosForm.setLogin(assistido.getLogin());
            dadosForm.setPerfilAcesso(assistido.getPerfilAcesso());
            dadosForm.setAcessoAtivo(assistido.isAcessoAtivo());
            dadosForm.setDiaFrequencia(assistido.getDiaFrequencia());
            dadosForm.setSenha(null);
            dadosForm.setVinculo(assistido.getVinculo());
            return exibirEdicao(model, dadosForm, loginLimpo, vaiCriarAcesso, authentication, e.getMessage());
        }

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
        assistido.setEmail(email);
        // Salvar a edição é a resolução do aviso de e-mail repetido da V35: o e-mail que está no
        // formulário agora é o que vale.
        assistido.setEmailConflito(null);
        // O vínculo NÃO vem mais do formulário (a categoria Trabalhador saiu daqui e virou o
        // módulo /trabalhadores): preservá-lo é essencial, senão editar os dados de um
        // trabalhador o rebaixaria a assistido sem querer.
        try {
            if (vaiCriarAcesso) {
                acessoService.criarAcesso(assistido, loginLimpo, senha);
                redirectAttributes.addFlashAttribute("sucesso", "Dados salvos e acesso criado. Login: \""
                        + assistido.getLogin() + "\"" + (assistido.getSenha() == null
                                ? " — entra pelo código enviado a " + assistido.getEmail() + "." : "."));
            } else {
                if (vaiAlterarAcesso) {
                    assistido.setLogin(loginLimpo);
                    if (senha != null && !senha.isBlank()) {
                        acessoService.trocarSenha(assistido, senha);
                    }
                    if (perfilPedido != null) {
                        assistido.setPerfilAcesso(perfilPedido);
                    }
                    assistido.setAcessoAtivo(acessoAtivo);
                }
                assistidoRepository.save(assistido);
                redirectAttributes.addFlashAttribute("sucesso", "Dados salvos.");
            }
        } catch (DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("erro",
                    "O e-mail ou o login acabou de ser usado em outro cadastro. Confira e salve de novo.");
            return "redirect:/prontuario/" + assistidoId + "/editar";
        }

        // Quem define o que o trabalhador alcança são as funções dele (ver TipoTrabalhador), não o
        // perfil de acesso: um login TRABALHADOR sem nenhuma função entra e não enxerga nada além
        // do próprio cartão. Avisa em vez de bloquear — a função pode ser atribuída depois.
        if (vaiAlterarAcesso && assistido.getPerfilAcesso() == PerfilAcesso.TRABALHADOR
                && trabalhadorRepository.findByAssistidoId(assistidoId)
                        .map(t -> t.getFuncoes().isEmpty()).orElse(true)) {
            redirectAttributes.addFlashAttribute("aviso",
                    "Este acesso é de trabalhador, mas o assistido não tem nenhuma função de trabalho:"
                            + " ele só vai enxergar o próprio cartão. Defina as funções em \"Cadastrar Trabalhador\".");
        }

        if (tem(authentication, Permissao.PRONTUARIO)) {
            try {
                tratamentoService.definirTratamento(assistido, dadosForm.getTratamentoAtual(), dataPrimeiraSessao);
            } catch (RegraNegocioException e) {
                redirectAttributes.addFlashAttribute("erro", e.getMessage());
                return "redirect:/prontuario/" + assistidoId + "/editar";
            }
        }

        return "redirect:/prontuario/" + assistidoId;
    }

    private static boolean tem(Authentication authentication, Permissao permissao) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(permissao.getAuthority()));
    }

    /**
     * Quem pode alterar um acesso que já existe (login, senha, ativo). Desde 2026-10-04 a Recepcionista
     * edita o login de <strong>assistidos</strong> ({@code CADASTRO}); o de trabalhadores exige quem
     * promove trabalhadores ({@code TRABALHADORES}, o Dirigente), e o de um Administrador só outro
     * Administrador. Sem essa escada, quem troca a senha de alguém com mais acesso entra como ele.
     */
    private boolean podeEditarAcessoDe(Authentication authentication, Assistido alvo) {
        if (ehAdministrador(authentication)) {
            return true;
        }
        PerfilAcesso perfilAlvo = alvo.getPerfilAcesso();
        if (perfilAlvo == PerfilAcesso.ADMINISTRADOR) {
            return false;
        }
        if (perfilAlvo == PerfilAcesso.TRABALHADOR) {
            return tem(authentication, Permissao.TRABALHADORES);
        }
        return tem(authentication, Permissao.CADASTRO);
    }

    private boolean podeEscolherPerfil(Authentication authentication) {
        return ehAdministrador(authentication) || tem(authentication, Permissao.TRABALHADORES);
    }

    /** O Dirigente escolhe perfil, mas o de Administrador só um Administrador concede. */
    private List<PerfilAcesso> perfisQuePodeConceder(Authentication authentication) {
        if (ehAdministrador(authentication)) {
            return List.of(PerfilAcesso.values());
        }
        return podeEscolherPerfil(authentication)
                ? List.of(PerfilAcesso.ASSISTIDO, PerfilAcesso.TRABALHADOR)
                : List.of();
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

    /**
     * Reenvia o código de entrada para quem entra por e-mail (ver CodigoAcessoService). É a saída da
     * recepção no atendimento presencial, quando a pessoa diz que o código não chegou — aqui não há o
     * que vazar sobre existência de cadastro, porque quem pede já está olhando o prontuário.
     */
    @PostMapping("/prontuario/{assistidoId}/reenviar-codigo")
    public String reenviarCodigoAcesso(@PathVariable Long assistidoId, RedirectAttributes redirectAttributes) {
        Assistido assistido = assistidoRepository.findById(assistidoId)
                .orElseThrow(() -> new IllegalArgumentException("Assistido inválido: " + assistidoId));

        if (codigoAcessoService.reenviarPara(assistido)) {
            redirectAttributes.addFlashAttribute("sucesso",
                    "Código enviado para " + assistido.getLogin() + ". Vale por 15 minutos.");
        } else {
            redirectAttributes.addFlashAttribute("erro",
                    "Este assistido não entra por código: o acesso dele não é um e-mail ativo.");
        }
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
        model.addAttribute("tratamentos", tipoTratamentoRepository.findAll());
        return "avaliacao-form";
    }

    // Mesmo cuidado do endpoint de sessão: path variable não pode se chamar "id" porque
    // Avaliacao também tem um campo "id". A Avaliação registra o diagnóstico (histórico,
    // observações, evolução) e, desde a V36, o tratamento PROPOSTO pelo Avaliador — que só passa a
    // valer quando a Entrevista o comunica (ver abaixo).
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

        try {
            tratamentoService.registrarAvaliacao(avaliacao);
        } catch (RegraNegocioException e) {
            redirectAttributes.addFlashAttribute("erro", e.getMessage());
            return "redirect:/prontuario/" + assistidoId + "/nova-avaliacao";
        }

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

        // A Entrevista comunica o que o Avaliador propôs (V36); o entrevistador confirma ou ajusta.
        // Avaliações antigas não têm proposta: aí vale o tratamento atual, como antes.
        Entrevista entrevista = new Entrevista();
        entrevista.setTratamentoIndicado(avaliacao.getTratamentoProposto() != null
                ? avaliacao.getTratamentoProposto() : assistido.getTratamentoAtual());

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
