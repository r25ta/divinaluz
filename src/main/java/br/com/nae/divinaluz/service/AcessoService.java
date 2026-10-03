package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Regras do acesso ao sistema, que desde 2026-10-03 é parte do próprio cadastro (form.html):
 *
 * <ul>
 *   <li><strong>Um e-mail por cadastro</strong> — o e-mail identifica a pessoa (a entrada por código
 *       procura por ele), e dois cadastros com o mesmo e-mail quase sempre são a mesma pessoa
 *       cadastrada duas vezes. Comparado sem maiúsculas, como o índice da V35.</li>
 *   <li><strong>Login único</strong>, também sem diferenciar maiúsculas, e <strong>sugerido a partir
 *       do nome</strong> ("Maria da Silva Souza" → {@code maria.souza}), com número no fim quando já
 *       existe. Não é mais o e-mail: o login é o que a pessoa digita na tela de login, e um nome curto
 *       é mais fácil de lembrar e de ditar na recepção.</li>
 *   <li><strong>Como a pessoa entra:</strong> com senha (digitada pela recepção) ou, se tem e-mail e o
 *       envio está ligado, por código enviado ao e-mail (ver {@link CodigoAcessoService}). Com o envio
 *       desligado — o padrão — a senha é obrigatória mesmo para quem tem e-mail, senão a pessoa
 *       ficaria sem nenhuma forma de entrar.</li>
 * </ul>
 */
@Service
public class AcessoService {

    public static final int SENHA_MINIMA = 6;
    private static final int LOGIN_MAXIMO = 80;
    private static final Pattern FORMATO_LOGIN = Pattern.compile("^[a-z0-9][a-z0-9._@+-]{2,79}$");
    private static final Pattern NAO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");
    // Partículas que não entram na sugestão: "Maria da Silva" vira maria.silva, não maria.da.
    private static final Set<String> PARTICULAS = Set.of("da", "de", "do", "das", "dos", "e", "d");

    private final AssistidoRepository assistidoRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Value("${app.base-url:http://localhost:8081/divinaluz}")
    private String baseUrl;

    public AcessoService(AssistidoRepository assistidoRepository, PasswordEncoder passwordEncoder,
            EmailService emailService) {
        this.assistidoRepository = assistidoRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    /** Resultado da verificação feita pelo formulário enquanto a recepção digita. */
    public record Verificacao(String sugestao, String problemaLogin, Assistido donoDoEmail) {}

    // ------------------------------------------------------------------ normalização

    /** E-mail como é gravado: sem espaços nas pontas, minúsculo, e em branco vira {@code null}. */
    public static String normalizarEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Login como é gravado: sem espaços nas pontas e minúsculo; em branco vira {@code null}. */
    public static String normalizarLogin(String login) {
        if (login == null || login.isBlank()) {
            return null;
        }
        return login.trim().toLowerCase(Locale.ROOT);
    }

    public boolean envioDeEmailLigado() {
        return emailService.isHabilitado();
    }

    /**
     * A senha só pode faltar quando a pessoa tem como entrar sem ela: e-mail no cadastro <em>e</em>
     * envio de e-mail ligado. Com o envio desligado, o código só iria para o log.
     */
    public boolean senhaObrigatoria(String email) {
        return normalizarEmail(email) == null || !envioDeEmailLigado();
    }

    // ------------------------------------------------------------------ consultas

    /** Quem já usa este e-mail, fora o próprio cadastro ({@code idIgnorado}). */
    public Optional<Assistido> donoDoEmail(String email, Long idIgnorado) {
        String normalizado = normalizarEmail(email);
        if (normalizado == null) {
            return Optional.empty();
        }
        return assistidoRepository.findFirstByEmailIgnoreCase(normalizado)
                .filter(dono -> !Objects.equals(dono.getId(), idIgnorado));
    }

    /** Mensagem do que impede este login, ou vazio se ele pode ser usado. */
    public Optional<String> problemaComLogin(String login, Long idIgnorado) {
        String normalizado = normalizarLogin(login);
        if (normalizado == null) {
            return Optional.of("Informe o login.");
        }
        if (normalizado.length() > LOGIN_MAXIMO || !FORMATO_LOGIN.matcher(normalizado).matches()) {
            return Optional.of("O login precisa ter de 3 a 80 caracteres: letras sem acento, números, "
                    + "ponto, hífen ou sublinhado, começando por letra ou número.");
        }
        boolean emUso = assistidoRepository.findFirstByLoginIgnoreCase(normalizado)
                .filter(dono -> !Objects.equals(dono.getId(), idIgnorado))
                .isPresent();
        return emUso ? Optional.of("O login \"" + normalizado + "\" já está em uso por outro cadastro.")
                : Optional.empty();
    }

    /**
     * Login sugerido a partir do nome: primeiro e último nome, sem acento e sem partículas
     * ("João da Silva" → {@code joao.silva}), com número no fim enquanto já existir
     * ({@code joao.silva2}, {@code joao.silva3}...). O login atual do próprio cadastro não conta como
     * ocupado.
     */
    public String sugerirLogin(String nome, Long idIgnorado) {
        String base = baseDoLogin(nome);
        String candidato = base;
        int sufixo = 2;
        while (problemaComLogin(candidato, idIgnorado).isPresent()) {
            candidato = base + sufixo;
            sufixo++;
        }
        return candidato;
    }

    static String baseDoLogin(String nome) {
        String semAcento = Normalizer.normalize(nome == null ? "" : nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        List<String> partes = new ArrayList<>();
        for (String parte : NAO_ALFANUMERICO.split(semAcento)) {
            if (!parte.isBlank() && !PARTICULAS.contains(parte)) {
                partes.add(parte);
            }
        }
        String base;
        if (partes.isEmpty()) {
            base = "assistido";
        } else if (partes.size() == 1) {
            base = partes.get(0);
        } else {
            base = partes.get(0) + "." + partes.get(partes.size() - 1);
        }
        // O formato exige ao menos 3 caracteres ("Li" sozinho não serviria) e no máximo 80, com
        // folga para o número de desempate.
        if (base.length() < 3) {
            base = "assistido." + base;
        }
        return base.length() > 70 ? base.substring(0, 70) : base;
    }

    /** O que o formulário mostra enquanto a recepção digita nome, login e e-mail. */
    public Verificacao verificar(String nome, String login, String email, Long idIgnorado) {
        String sugestao = nome == null || nome.isBlank() ? null : sugerirLogin(nome, idIgnorado);
        String problema = login == null || login.isBlank() ? null
                : problemaComLogin(login, idIgnorado).orElse(null);
        return new Verificacao(sugestao, problema, donoDoEmail(email, idIgnorado).orElse(null));
    }

    // ------------------------------------------------------------------ validação e gravação

    /** Recusa e-mail já usado por outro cadastro, dizendo de quem é para a recepção não duplicar. */
    public void validarEmail(String email, Long idIgnorado) {
        donoDoEmail(email, idIgnorado).ifPresent(dono -> {
            throw new RegraNegocioException("O e-mail \"" + normalizarEmail(email) + "\" já está no cadastro de "
                    + dono.getNome() + (dono.isAtivo() ? "" : " (cadastro desativado)")
                    + ". Cada e-mail só pode estar em um cadastro.");
        });
    }

    public void validarLogin(String login, Long idIgnorado) {
        problemaComLogin(login, idIgnorado).ifPresent(problema -> {
            throw new RegraNegocioException(problema);
        });
    }

    /**
     * Confere a senha digitada. Em branco é aceito só quando {@code obrigatoria} é falso (quem entra
     * por código, ou a edição que mantém a senha atual).
     */
    public void validarSenha(String senha, String confirmacao, boolean obrigatoria, String porQueObrigatoria) {
        boolean informada = senha != null && !senha.isBlank();
        if (!informada) {
            if (obrigatoria) {
                throw new RegraNegocioException(porQueObrigatoria);
            }
            return;
        }
        if (senha.length() < SENHA_MINIMA) {
            throw new RegraNegocioException("A senha precisa ter pelo menos " + SENHA_MINIMA + " caracteres.");
        }
        if (!senha.equals(confirmacao)) {
            throw new RegraNegocioException("A confirmação da senha não confere.");
        }
    }

    /** Explica por que a senha é obrigatória para este e-mail, na linguagem da recepção. */
    public String motivoSenhaObrigatoria(String email) {
        if (normalizarEmail(email) == null) {
            return "Sem e-mail, informe uma senha de acesso para a pessoa (mínimo de " + SENHA_MINIMA + " caracteres).";
        }
        return "O envio de e-mail não está ligado, então a pessoa não receberia o código de entrada: "
                + "informe uma senha de acesso (mínimo de " + SENHA_MINIMA + " caracteres).";
    }

    /**
     * Cria o acesso de quem ainda não tem, já validado pelo chamador ({@link #validarLogin},
     * {@link #validarSenha}). O perfil nasce TRABALHADOR para quem já foi promovido em "Cadastrar
     * Trabalhador", e ASSISTIDO para os demais.
     */
    public void criarAcesso(Assistido assistido, String login, String senha) {
        assistido.setLogin(normalizarLogin(login));
        assistido.setPerfilAcesso(TrabalhadorService.VINCULO_TRABALHADOR.equals(assistido.getVinculo())
                ? PerfilAcesso.TRABALHADOR : PerfilAcesso.ASSISTIDO);
        assistido.setAcessoAtivo(true);
        assistido.setSenha(senha == null || senha.isBlank() ? null : passwordEncoder.encode(senha));
        assistido.setTokenDefinicaoSenha(null);
        assistido.setTokenDefinicaoSenhaExpiraEm(null);
        assistidoRepository.save(assistido);

        // Só avisa por e-mail quem pode entrar por código — com o envio desligado isto só iria ao log.
        if (assistido.getEmail() != null && envioDeEmailLigado()) {
            emailService.enviarBoasVindas(assistido.getEmail(), assistido.getNome(), baseUrl + "/entrar");
        }
    }

    public void trocarSenha(Assistido assistido, String senha) {
        assistido.setSenha(passwordEncoder.encode(senha));
    }
}
