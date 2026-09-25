package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

// Perfil ASSISTIDO é somente consulta (só o próprio cartão e a escala de preleções): as telas
// usam estes atributos para esconder links e botões de inclusão/alteração/exclusão. A barreira
// real continua sendo o SecurityConfig — isto é só apresentação.
@ControllerAdvice
public class PerfilAdvice {

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
}
