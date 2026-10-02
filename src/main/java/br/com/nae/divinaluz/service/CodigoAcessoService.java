package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Entrada sem senha: o assistido digita o e-mail do cadastro, recebe um código de 6 dígitos e entra
 * com ele. Substitui o link de definição de senha da V30 para quem tem e-mail, por um motivo prático:
 * o e-mail está no celular da pessoa e o sistema costuma estar aberto no computador da recepção — um
 * link clicado no celular loga o celular, um código atravessa essa distância.
 *
 * <p>Quem <strong>não</strong> tem e-mail continua com a senha que a recepção digita no cadastro (ver
 * {@link AcessoService}); esse caminho não é legado, é o par deste.</p>
 *
 * <p>Um código de 6 dígitos é uma credencial fraca (1 milhão de combinações), então as quatro defesas
 * abaixo não são enfeite — sem elas isto é pior que senha:</p>
 * <ul>
 *   <li><strong>Hash:</strong> o banco guarda o BCrypt do código, nunca o código (há dump diário).</li>
 *   <li><strong>Vida curta:</strong> {@value #VALIDADE_MINUTOS} minutos, contra as 48h do token da V30,
 *       que tinha 64 caracteres aleatórios e podia se dar esse luxo.</li>
 *   <li><strong>Tentativas:</strong> {@value #MAXIMO_TENTATIVAS} erros e o código é descartado — é o
 *       que torna a força bruta inviável.</li>
 *   <li><strong>Intervalo entre pedidos:</strong> {@value #INTERVALO_MINIMO_SEGUNDOS}s, para o
 *       formulário não virar bomba de e-mail na caixa de uma pessoa (e consumo de cota do provedor).</li>
 * </ul>
 *
 * <p><strong>Não revela quem é cadastrado:</strong> {@link #solicitar(String)} não devolve nada e a
 * tela mostra a mesma mensagem para e-mail conhecido e desconhecido. Aqui isso importa mais que o
 * normal — confirmar que um e-mail está cadastrado num centro espírita revela afiliação religiosa de
 * uma pessoa identificável.</p>
 */
@Service
public class CodigoAcessoService {

    private static final Logger log = LoggerFactory.getLogger(CodigoAcessoService.class);

    static final int VALIDADE_MINUTOS = 15;
    static final int MAXIMO_TENTATIVAS = 5;
    static final int INTERVALO_MINIMO_SEGUNDOS = 60;

    private final AssistidoRepository assistidoRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();

    public CodigoAcessoService(AssistidoRepository assistidoRepository, PasswordEncoder passwordEncoder,
            EmailService emailService) {
        this.assistidoRepository = assistidoRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    /** Resultado da conferência do código. Só {@link #OK} carrega o assistido. */
    public enum Resultado {
        OK, INVALIDO, EXPIRADO, BLOQUEADO
    }

    public record Conferencia(Resultado resultado, Assistido assistido) {
        public boolean ok() {
            return resultado == Resultado.OK;
        }
    }

    /**
     * Gera e envia um código para o e-mail, <strong>se</strong> ele for o login de um acesso ativo.
     * Não devolve nada de propósito: quem chama não pode distinguir e-mail cadastrado de
     * desconhecido, senão a tela vira um verificador de quem frequenta a casa.
     */
    @Transactional
    public void solicitar(String email) {
        String login = email == null ? "" : email.trim();
        if (login.isEmpty()) {
            return;
        }
        Optional<Assistido> encontrado = assistidoRepository.findByLoginIgnoreCaseAndAcessoAtivoTrue(login);
        if (encontrado.isEmpty()) {
            log.debug("Pedido de código para e-mail sem acesso ativo; nada enviado.");
            return;
        }
        Assistido assistido = encontrado.get();

        if (assistido.getCodigoAcessoEnviadoEm() != null && assistido.getCodigoAcessoEnviadoEm()
                .isAfter(LocalDateTime.now().minusSeconds(INTERVALO_MINIMO_SEGUNDOS))) {
            log.debug("Pedido de código repetido dentro do intervalo mínimo; nada enviado.");
            return;
        }
        gerarEEnviar(assistido);
    }

    /**
     * Confere o código daquele e-mail. Em caso de acerto, limpa o código (uso único) e garante o
     * acesso ativo; em caso de erro, conta a tentativa e descarta o código ao atingir o limite.
     */
    @Transactional
    public Conferencia conferir(String email, String codigoInformado) {
        String login = email == null ? "" : email.trim();
        String codigo = codigoInformado == null ? "" : codigoInformado.replaceAll("\\s", "");
        Optional<Assistido> encontrado = login.isEmpty()
                ? Optional.empty()
                : assistidoRepository.findByLoginIgnoreCaseAndAcessoAtivoTrue(login);
        if (encontrado.isEmpty()) {
            return new Conferencia(Resultado.INVALIDO, null);
        }
        Assistido assistido = encontrado.get();

        if (assistido.getCodigoAcesso() == null || assistido.getCodigoAcessoExpiraEm() == null) {
            return new Conferencia(Resultado.EXPIRADO, null);
        }
        if (assistido.getCodigoAcessoExpiraEm().isBefore(LocalDateTime.now())) {
            limparCodigo(assistido);
            return new Conferencia(Resultado.EXPIRADO, null);
        }
        if (assistido.getCodigoAcessoTentativas() >= MAXIMO_TENTATIVAS) {
            limparCodigo(assistido);
            return new Conferencia(Resultado.BLOQUEADO, null);
        }

        if (!passwordEncoder.matches(codigo, assistido.getCodigoAcesso())) {
            assistido.setCodigoAcessoTentativas(assistido.getCodigoAcessoTentativas() + 1);
            boolean estourou = assistido.getCodigoAcessoTentativas() >= MAXIMO_TENTATIVAS;
            if (estourou) {
                limparCodigo(assistido);
            } else {
                assistidoRepository.save(assistido);
            }
            return new Conferencia(estourou ? Resultado.BLOQUEADO : Resultado.INVALIDO, null);
        }

        limparCodigo(assistido);
        return new Conferencia(Resultado.OK, assistido);
    }

    /**
     * Reenvio pedido pela recepção a partir do prontuário, para o caso de domingo de manhã em que a
     * pessoa diz que o código não chegou. Diferente de {@link #solicitar(String)}, aqui quem chama é
     * staff e já sabe de quem se trata, então pode saber se havia e-mail ou não — não há o que vazar.
     *
     * @return {@code true} se havia um login de e-mail para onde enviar.
     */
    @Transactional
    public boolean reenviarPara(Assistido assistido) {
        if (assistido.getLogin() == null || !assistido.getLogin().contains("@") || !assistido.isAcessoAtivo()) {
            return false;
        }
        // O intervalo mínimo de propósito não se aplica aqui: o pedido vem de um atendimento
        // presencial, e travar a recepção por um minuto seria pior que o risco que o intervalo evita.
        gerarEEnviar(assistido);
        return true;
    }

    private void gerarEEnviar(Assistido assistido) {
        String codigo = gerarCodigo();
        assistido.setCodigoAcesso(passwordEncoder.encode(codigo));
        assistido.setCodigoAcessoExpiraEm(LocalDateTime.now().plusMinutes(VALIDADE_MINUTOS));
        assistido.setCodigoAcessoTentativas(0);
        assistido.setCodigoAcessoEnviadoEm(LocalDateTime.now());
        assistidoRepository.save(assistido);

        emailService.enviarCodigoAcesso(assistido.getLogin(), assistido.getNome(), codigo, VALIDADE_MINUTOS);
    }

    private void limparCodigo(Assistido assistido) {
        assistido.setCodigoAcesso(null);
        assistido.setCodigoAcessoExpiraEm(null);
        assistido.setCodigoAcessoTentativas(0);
        assistido.setAcessoAtivo(true);
        assistidoRepository.save(assistido);
    }

    /** Seis dígitos, podendo começar com zero (é texto, não número). */
    private String gerarCodigo() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
