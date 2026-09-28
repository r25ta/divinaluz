package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cria o acesso (perfil ASSISTIDO) automaticamente no cadastro completo (ver
 * ProntuarioController.salvar) — item "cadastro de assistido gera acesso" do CLAUDE.md.
 *
 * <ul>
 *   <li>Com e-mail: login = e-mail, senha fica pendente e um link de definição por token é
 *       enviado (ver EmailService). O assistido só consegue logar depois de definir a senha
 *       (UserDetailsService do SecurityConfig já filtra {@code senha != null}).</li>
 *   <li>Sem e-mail: a recepção informa a senha na hora do cadastro (campo "Senha de Acesso" em
 *       form.html) e o login é gerado a partir do nome do assistido, de fácil memorização.</li>
 * </ul>
 *
 * Não bloqueia o cadastro do assistido em si: uma falha aqui (ex.: e-mail já usado como login por
 * outro acesso) só deixa de criar o acesso, sinalizada por {@link ResultadoAcesso#aviso()}.
 */
@Service
public class AcessoService {

    private static final Pattern NAO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");
    private static final int VALIDADE_TOKEN_HORAS = 48;

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

    public record ResultadoAcesso(boolean criado, String aviso) {
    }

    /**
     * @param senhaInformada senha digitada pela recepção quando o assistido não tem e-mail
     *                       (ignorada, se informada, quando há e-mail — nesse caso o próprio
     *                       assistido define a senha pelo link).
     */
    public ResultadoAcesso criarAcessoAutomatico(Assistido assistido, String senhaInformada) {
        String emailLimpo = assistido.getEmail() == null ? "" : assistido.getEmail().trim();
        boolean temEmail = !emailLimpo.isEmpty();

        String login = temEmail ? emailLimpo : gerarLoginPorNome(assistido.getNome());
        if (temEmail && assistidoRepository.existsByLogin(login)) {
            return new ResultadoAcesso(false,
                    "Assistido cadastrado, mas já existe um acesso com o e-mail \"" + login + "\" — "
                            + "crie o acesso manualmente pelo prontuário, com outro login.");
        }

        assistido.setLogin(login);
        assistido.setPerfilAcesso(PerfilAcesso.ASSISTIDO);
        assistido.setAcessoAtivo(true);

        if (temEmail) {
            String token = gerarToken();
            assistido.setSenha(null);
            assistido.setTokenDefinicaoSenha(token);
            assistido.setTokenDefinicaoSenhaExpiraEm(LocalDateTime.now().plusHours(VALIDADE_TOKEN_HORAS));
            assistidoRepository.save(assistido);

            String link = baseUrl + "/definir-senha/" + token;
            emailService.enviarDefinicaoSenha(emailLimpo, assistido.getNome(), link);
        } else {
            assistido.setSenha(passwordEncoder.encode(senhaInformada));
            assistido.setTokenDefinicaoSenha(null);
            assistido.setTokenDefinicaoSenhaExpiraEm(null);
            assistidoRepository.save(assistido);
        }

        return new ResultadoAcesso(true, null);
    }

    // Login de fácil memorização a partir do nome (ex.: "João da Silva" -> "joao.silva"), sem
    // acento/espaço, com sufixo numérico se já existir (joao.silva2, joao.silva3...).
    private String gerarLoginPorNome(String nome) {
        String semAcento = Normalizer.normalize(nome == null ? "" : nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .trim();
        String base = NAO_ALFANUMERICO.matcher(semAcento).replaceAll(".").replaceAll("^\\.+|\\.+$", "");
        if (base.isBlank()) {
            base = "assistido";
        }
        String candidato = base;
        int sufixo = 2;
        while (assistidoRepository.existsByLogin(candidato)) {
            candidato = base + sufixo;
            sufixo++;
        }
        return candidato;
    }

    private String gerarToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
