package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CelularVinculado;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.CelularVinculadoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Celular vinculado ao cartão (2026-10-05): o assistido marca a presença pelo QR da sessão sem
 * login. No domingo de 04/10 cada leitura do QR caía no login, e boa parte do público da casa não
 * tem (ou não lembra) login e senha.
 *
 * <p>O celular é vinculado <strong>uma vez</strong> — pelo convite que a recepção mostra no
 * prontuário, ou pela própria pessoa ao marcar a presença já logada — e guarda num cookie um token
 * aleatório. Esse token <strong>só serve para marcar a presença do dono</strong> pelo QR da sessão:
 * não autentica, não abre o cartão nem nada mais do sistema. Quem o copiar consegue, no máximo,
 * marcar a presença do dono, e ainda assim só estando diante do QR da sessão (que muda a cada
 * minuto). A recepção desvincula todos os celulares de alguém pelo prontuário.</p>
 */
@Service
public class CelularVinculadoService {

    /** Pessoas por celular: uma família, não a fila inteira passando pelo mesmo aparelho. */
    public static final int MAXIMO_DE_PESSOAS = 6;

    /** Validade do convite que a recepção mostra no prontuário. */
    public static final int VALIDADE_CONVITE_MINUTOS = 10;

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final CelularVinculadoRepository celularVinculadoRepository;
    private final AssistidoRepository assistidoRepository;

    public CelularVinculadoService(CelularVinculadoRepository celularVinculadoRepository,
            AssistidoRepository assistidoRepository) {
        this.celularVinculadoRepository = celularVinculadoRepository;
        this.assistidoRepository = assistidoRepository;
    }

    /** Uma pessoa vinculada a este celular, com o token dela no cookie. */
    public record PessoaDoCelular(String token, Assistido assistido) {}

    /**
     * Quem está vinculado a este celular, pelos tokens do cookie. Tokens desconhecidos, desvinculados
     * pela recepção ou de cadastro desativado ficam de fora — quem chama regrava o cookie sem eles.
     */
    @Transactional
    public List<PessoaDoCelular> identificar(List<String> tokens) {
        Map<Long, PessoaDoCelular> porPessoa = new LinkedHashMap<>();
        for (String token : tokens) {
            celularVinculadoRepository.findByTokenHash(hash(token))
                    .filter(celular -> celular.getAssistido().isAtivo())
                    .ifPresent(celular -> {
                        celular.setUltimoUsoEm(LocalDateTime.now());
                        porPessoa.putIfAbsent(celular.getAssistido().getId(),
                                new PessoaDoCelular(token, celular.getAssistido()));
                    });
        }
        return new ArrayList<>(porPessoa.values());
    }

    /**
     * Vincula mais uma pessoa a este celular e devolve os tokens que o cookie passa a carregar. Se ela
     * já estava neste celular, o vínculo antigo é trocado pelo novo (não acumula).
     */
    @Transactional
    public List<String> vincular(List<PessoaDoCelular> doCelular, Assistido pessoa) {
        List<String> tokens = new ArrayList<>();
        for (PessoaDoCelular atual : doCelular) {
            if (atual.assistido().getId().equals(pessoa.getId())) {
                desvincular(atual.token());
            } else {
                tokens.add(atual.token());
            }
        }
        if (tokens.size() >= MAXIMO_DE_PESSOAS) {
            throw new RegraNegocioException("Este celular já está vinculado a " + MAXIMO_DE_PESSOAS
                    + " pessoas, o máximo. Desvincule-o e vincule de novo só quem o usa.");
        }
        String token = tokenAleatorio(32);
        CelularVinculado celular = new CelularVinculado();
        celular.setAssistido(pessoa);
        celular.setTokenHash(hash(token));
        celular.setVinculadoEm(LocalDateTime.now());
        celularVinculadoRepository.save(celular);
        tokens.add(token);
        return tokens;
    }

    /** Desvincula um token (o botão "Desvincular este celular" desfaz um a um). */
    @Transactional
    public void desvincular(String token) {
        if (token != null && !token.isBlank()) {
            celularVinculadoRepository.findByTokenHash(hash(token)).ifPresent(celularVinculadoRepository::delete);
        }
    }

    /** Desvincula todos os celulares da pessoa (recepção, pelo prontuário). Devolve quantos eram. */
    @Transactional
    public long desvincularTodos(Long assistidoId) {
        return celularVinculadoRepository.deleteByAssistidoId(assistidoId);
    }

    public long quantosVinculados(Long assistidoId) {
        return celularVinculadoRepository.countByAssistidoId(assistidoId);
    }

    /**
     * Convite de vínculo para a recepção mostrar em QR: uso único, {@value #VALIDADE_CONVITE_MINUTOS}
     * minutos. Gerar outro substitui o anterior.
     */
    @Transactional
    public String gerarConvite(Assistido assistido) {
        if (!assistido.isAtivo()) {
            throw new RegraNegocioException("Este prontuário está desativado: reative-o antes de vincular um celular.");
        }
        String convite = tokenAleatorio(24);
        assistido.setConviteCelularHash(hash(convite));
        assistido.setConviteCelularExpiraEm(LocalDateTime.now().plusMinutes(VALIDADE_CONVITE_MINUTOS));
        assistidoRepository.save(assistido);
        return convite;
    }

    /** A quem o convite vincularia, sem consumi-lo (a tela de confirmação). */
    public Assistido conferirConvite(String convite) {
        Assistido assistido = Optional.ofNullable(convite).filter(c -> !c.isBlank())
                .flatMap(c -> assistidoRepository.findByConviteCelularHash(hash(c)))
                .orElseThrow(() -> new RegraNegocioException(
                        "Este QR de vínculo não vale mais. Peça à recepção para mostrar outro."));
        if (!assistido.isAtivo() || assistido.getConviteCelularExpiraEm() == null
                || assistido.getConviteCelularExpiraEm().isBefore(LocalDateTime.now())) {
            throw new RegraNegocioException(
                    "Este QR de vínculo expirou (vale " + VALIDADE_CONVITE_MINUTOS + " minutos). "
                            + "Peça à recepção para mostrar outro.");
        }
        return assistido;
    }

    /** Consome o convite (uso único) e vincula a pessoa a este celular. Devolve os tokens do cookie. */
    @Transactional
    public List<String> aceitarConvite(String convite, List<PessoaDoCelular> doCelular) {
        Assistido assistido = conferirConvite(convite);
        List<String> tokens = vincular(doCelular, assistido);
        assistido.setConviteCelularHash(null);
        assistido.setConviteCelularExpiraEm(null);
        assistidoRepository.save(assistido);
        return tokens;
    }

    private static String tokenAleatorio(int bytes) {
        byte[] aleatorio = new byte[bytes];
        ALEATORIO.nextBytes(aleatorio);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(aleatorio);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível.", e);
        }
    }
}
