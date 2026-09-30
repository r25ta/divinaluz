package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

// Perfil ASSISTIDO é somente consulta (só o próprio cartão e a escala de preleções): as telas
// usam estes atributos para esconder links e botões de inclusão/alteração/exclusão. A barreira
// real continua sendo o SecurityConfig — isto é só apresentação.
@ControllerAdvice
public class PerfilAdvice {

    private static final List<CartaoStatus> STATUS_FILA_ENTREVISTA =
            List.of(CartaoStatus.AGUARDANDO_AVALIACAO, CartaoStatus.AGUARDANDO_ENTREVISTA);

    private final AssistidoRepository assistidoRepository;

    public PerfilAdvice(AssistidoRepository assistidoRepository) {
        this.assistidoRepository = assistidoRepository;
    }

    @ModelAttribute("somenteConsulta")
    public boolean somenteConsulta(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ASSISTIDO"));
    }

    @ModelAttribute("meuAssistidoId")
    public Long meuAssistidoId(Authentication authentication) {
        if (!somenteConsulta(authentication)) {
            return null;
        }
        return assistidoRepository.findByLoginAndAcessoAtivoTrue(authentication.getName())
                .map(Assistido::getId).orElse(null);
    }

    // Badge da navbar (link "Entrevistas") com o tamanho da fila — só para staff, para o link já
    // avisar quanto tem represado sem precisar entrar. Ver EntrevistaController.
    @ModelAttribute("filaEntrevistas")
    public long filaEntrevistas(Authentication authentication) {
        if (authentication == null || somenteConsulta(authentication)) {
            return 0;
        }
        return assistidoRepository.countByStatusCartaoInAndAtivoTrue(STATUS_FILA_ENTREVISTA);
    }
}
