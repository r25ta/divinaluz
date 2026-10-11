package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.model.Permissao;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * A antiga fila de avaliação/entrevista (Módulo de Entrevista, 3.16). Desde 2026-10-11 a fila é a
 * própria listagem de Prontuário, com o filtro por status e a ação de cada linha; este endereço só
 * leva até ela, já filtrada no que a pessoa atende, para não quebrar favoritos.
 */
@Controller
public class EntrevistaController {

    @GetMapping("/entrevistas")
    public String fila(@AuthenticationPrincipal UserDetails usuarioLogado) {
        boolean avaliacao = tem(usuarioLogado, Permissao.AVALIACAO);
        boolean entrevista = tem(usuarioLogado, Permissao.ENTREVISTA);
        String filtro = avaliacao && !entrevista ? CartaoStatus.AGUARDANDO_AVALIACAO.name()
                : entrevista && !avaliacao ? CartaoStatus.AGUARDANDO_ENTREVISTA.name()
                : "TODOS";
        return "redirect:/?status=" + filtro;
    }

    private static boolean tem(UserDetails usuarioLogado, Permissao permissao) {
        return usuarioLogado != null && usuarioLogado.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(permissao.getAuthority()));
    }
}
