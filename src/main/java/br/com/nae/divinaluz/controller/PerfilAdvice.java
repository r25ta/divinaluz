package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.Permissao;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

// Visibilidade por perfil de trabalho: as telas usam estes atributos para esconder os links e
// botões dos módulos que a pessoa não alcança (ver TipoTrabalhador/Permissao). A barreira real
// continua sendo o SecurityConfig — isto é só apresentação, para não oferecer o que daria 403.
@ControllerAdvice
public class PerfilAdvice {

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
    public Long meuAssistidoId(Authentication authentication, HttpServletRequest request) {
        if (authentication == null || !ehPagina(request)) {
            return null;
        }
        // O id de quem está logado não muda durante a sessão: guardado na sessão HTTP, em vez de uma
        // consulta ao banco a cada página. A chave leva o login, para uma troca de usuário na mesma
        // sessão não herdar o id do anterior.
        HttpSession sessao = request.getSession(false);
        String chave = "meuAssistidoId:" + authentication.getName();
        if (sessao != null && sessao.getAttribute(chave) instanceof Long guardado) {
            return guardado;
        }
        Long id = assistidoRepository.findByLoginAndAcessoAtivoTrue(authentication.getName())
                .map(Assistido::getId).orElse(null);
        if (sessao != null && id != null) {
            sessao.setAttribute(chave, id);
        }
        return id;
    }

    /**
     * Só as páginas inteiras usam estes atributos (navbar, botões). Os pedidos de pedaço de tela
     * (atualização dos indicadores a cada 10s, busca da recepção — enviados com X-Requested-With) e
     * as imagens de QR não: para eles nenhuma consulta ao banco é feita aqui (2026-10-05).
     */
    private static boolean ehPagina(HttpServletRequest request) {
        return request.getHeader("X-Requested-With") == null && !request.getRequestURI().endsWith(".png");
    }

    @ModelAttribute("podeConsulta")
    public boolean podeConsulta(Authentication authentication) {
        return pode(authentication, Permissao.CONSULTA);
    }

    @ModelAttribute("podeCadastro")
    public boolean podeCadastro(Authentication authentication) {
        return pode(authentication, Permissao.CADASTRO);
    }

    @ModelAttribute("podeProntuario")
    public boolean podeProntuario(Authentication authentication) {
        return pode(authentication, Permissao.PRONTUARIO);
    }

    @ModelAttribute("podeAvaliacao")
    public boolean podeAvaliacao(Authentication authentication) {
        return pode(authentication, Permissao.AVALIACAO);
    }

    /**
     * Quem enxerga o que o Avaliador decidiu (tratamento proposto ou alta, e as recomendações)
     * enquanto o cartão aguarda a entrevista: só quem entrevista — Entrevistador e Dirigente
     * (2026-10-07; de 2026-10-04 até ali o Avaliador também via). Sobre o próprio cartão, ninguém:
     * o prontuário nem abre (ProntuarioController.verProntuario) e a fila esconde a própria linha.
     */
    @ModelAttribute("podeVerTratamentoProposto")
    public boolean podeVerTratamentoProposto(Authentication authentication) {
        return pode(authentication, Permissao.ENTREVISTA);
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

    // Badge da navbar (link "Entrevistas") com o que a pessoa tem para atender: o Avaliador conta os
    // cartões aguardando avaliação, o Entrevistador os aguardando entrevista, e quem faz as duas
    // coisas (Dirigente) conta os dois. Ver EntrevistaController.
    @ModelAttribute("filaEntrevistas")
    public long filaEntrevistas(Authentication authentication, HttpServletRequest request) {
        if (!ehPagina(request)) {
            return 0;
        }
        List<CartaoStatus> meus = new java.util.ArrayList<>();
        if (pode(authentication, Permissao.AVALIACAO)) {
            meus.add(CartaoStatus.AGUARDANDO_AVALIACAO);
        }
        if (pode(authentication, Permissao.ENTREVISTA)) {
            meus.add(CartaoStatus.AGUARDANDO_ENTREVISTA);
        }
        return meus.isEmpty() ? 0 : assistidoRepository.countByStatusCartaoInAndAtivoTrue(meus);
    }

    private boolean pode(Authentication authentication, Permissao permissao) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(concedida -> concedida.getAuthority().equals(permissao.getAuthority()));
    }
}
