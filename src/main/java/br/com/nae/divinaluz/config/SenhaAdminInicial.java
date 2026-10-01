package br.com.nae.divinaluz.config;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Troca a senha do {@code admin} na subida, quando {@code ADMIN_SENHA_REDEFINIR} vem preenchida (ver
 * DEPLOY-NUVEM.md). Existe porque num endereço público a senha que veio das migrations não serve: ela
 * está no repositório como hash e é a mesma de todo mundo que clonou o projeto.
 *
 * <p>A variável é de <strong>uso único</strong>: ela vale a cada subida enquanto existir, então, depois
 * do primeiro acesso, apague-a na plataforma — senão um redeploy desfaz qualquer troca de senha feita
 * pela tela. Em branco (o caso normal, e sempre em dev) este runner não faz nada.
 */
@Component
@Profile("prod")
public class SenhaAdminInicial implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SenhaAdminInicial.class);
    private static final String LOGIN_ADMIN = "admin";
    private static final int TAMANHO_MINIMO = 8;

    private final AssistidoRepository assistidoRepository;
    private final PasswordEncoder passwordEncoder;
    private final String senhaNova;
    private final String baseUrl;

    public SenhaAdminInicial(AssistidoRepository assistidoRepository, PasswordEncoder passwordEncoder,
            @Value("${app.admin.senha-redefinir:}") String senhaNova,
            @Value("${app.base-url:}") String baseUrl) {
        this.assistidoRepository = assistidoRepository;
        this.passwordEncoder = passwordEncoder;
        this.senhaNova = senhaNova;
        this.baseUrl = baseUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        avisarSeFaltaBaseUrl();
        if (senhaNova == null || senhaNova.isBlank()) {
            return;
        }
        if (senhaNova.trim().length() < TAMANHO_MINIMO) {
            throw new IllegalStateException("ADMIN_SENHA_REDEFINIR tem menos de " + TAMANHO_MINIMO
                    + " caracteres. Numa URL pública isso não serve: escolha uma senha maior.");
        }
        Assistido admin = assistidoRepository.findByLogin(LOGIN_ADMIN)
                .orElseThrow(() -> new IllegalStateException(
                        "Login " + LOGIN_ADMIN + " não encontrado (deveria vir das migrations V19–V25)."));
        admin.setSenha(passwordEncoder.encode(senhaNova.trim()));
        admin.setAcessoAtivo(true);
        assistidoRepository.save(admin);
        log.warn("Senha do {} redefinida por ADMIN_SENHA_REDEFINIR. APAGUE essa variável na plataforma agora: "
                + "enquanto ela existir, cada subida volta a gravar esta senha.", LOGIN_ADMIN);
    }

    /**
     * Sem {@code APP_BASE_URL} o link do e-mail de definição de senha sai apontando para o localhost.
     * É um aviso e não um erro porque o sistema funciona sem e-mail: a recepção digita a senha no
     * cadastro de quem não tem endereço (ver AcessoService).
     */
    private void avisarSeFaltaBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("APP_BASE_URL não definida: o link de definição de senha vai sair errado. "
                    + "Defina-a com o endereço público, sem barra no fim.");
        }
    }
}
