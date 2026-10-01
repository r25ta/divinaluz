package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.Permissao;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

// Visibilidade por perfil de trabalho: as telas usam estes atributos para esconder os links e
// botões dos módulos que a pessoa não alcança (ver TipoTrabalhador/Permissao). A barreira real
// continua sendo o SecurityConfig — isto é só apresentação, para não oferecer o que daria 403.
@ControllerAdvice
public class PerfilAdvice {

    private static final List<CartaoStatus> STATUS_FILA_ENTREVISTA =
            List.of(CartaoStatus.AGUARDANDO_AVALIACAO, CartaoStatus.AGUARDANDO_ENTREVISTA);

    private final AssistidoRepository assistidoRepository;

    public PerfilAdvice(AssistidoRepository assistidoRepository) {
        this.assistidoRepository = assistidoRepository;
    }

    /**
     * Id do assistido logado — usado para levar ao próprio cartão. Resolve para qualquer um que
     * esteja logado (não só para o perfil ASSISTIDO), porque o trabalhador sem permissão de
     * consulta também precisa de um caminho até o seu cartão.
     */
    @ModelAttribute("meuAssistidoId")
    public Long meuAssistidoId(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return assistidoRepository.findByLoginAndAcessoAtivoTrue(authentication.getName())
                .map(Assistido::getId).orElse(null);
    }

    @ModelAttribute("podeConsulta")
    public boolean podeConsulta(Authentication authentication) {
        return pode(authentication, Permissao.CONSULTA);
    }

    @ModelAttribute("podeCadastro")
    public boolean podeCadastro(Authentication authentication) {
        return pode(authentication, Permissao.CADASTRO);
    }

    @ModelAttribute("podeSessao")
    public boolean podeSessao(Authentication authentication) {
        return pode(authentication, Permissao.SESSAO);
    }

    @ModelAttribute("podeEntrevista")
    public boolean podeEntrevista(Authentication authentication) {
        return pode(authentication, Permissao.ENTREVISTA);
    }

    @ModelAttribute("podePrelecao")
    public boolean podePrelecao(Authentication authentication) {
        return pode(authentication, Permissao.PRELECAO);
    }

    @ModelAttribute("podeTrabalhadores")
    public boolean podeTrabalhadores(Authentication authentication) {
        return pode(authentication, Permissao.TRABALHADORES);
    }

    // Badge da navbar (link "Entrevistas") com o tamanho da fila, para o link já avisar quanto tem
    // represado sem precisar entrar. Só para quem conduz entrevistas. Ver EntrevistaController.
    @ModelAttribute("filaEntrevistas")
    public long filaEntrevistas(Authentication authentication) {
        if (!pode(authentication, Permissao.ENTREVISTA)) {
            return 0;
        }
        return assistidoRepository.countByStatusCartaoInAndAtivoTrue(STATUS_FILA_ENTREVISTA);
    }

    private boolean pode(Authentication authentication, Permissao permissao) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(concedida -> concedida.getAuthority().equals(permissao.getAuthority()));
    }
}
