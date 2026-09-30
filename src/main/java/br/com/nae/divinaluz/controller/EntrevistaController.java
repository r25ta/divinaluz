package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.Avaliacao;
import br.com.nae.divinaluz.model.CartaoStatus;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.AvaliacaoRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Módulo de Entrevista (item 4 da seção 8 do CLAUDE.md): fila dos cartões retidos — Aguardando
 * Avaliação e Aguardando Entrevista — para o avaliador/entrevistador atender sem precisar caçar
 * cada prontuário manualmente. Todas as regras de negócio continuam intactas em
 * {@code TratamentoService}; este controller só monta a fila e aponta para os formulários que já
 * existem ({@code GET/POST /prontuario/{id}/nova-avaliacao} e {@code /nova-entrevista}), inclusive
 * o bloqueio de "ninguém conduz o próprio tratamento" (regra aplicada lá, não duplicada aqui).
 */
@Controller
public class EntrevistaController {

    private final AssistidoRepository assistidoRepository;
    private final AvaliacaoRepository avaliacaoRepository;

    public EntrevistaController(AssistidoRepository assistidoRepository, AvaliacaoRepository avaliacaoRepository) {
        this.assistidoRepository = assistidoRepository;
        this.avaliacaoRepository = avaliacaoRepository;
    }

    // A avaliação pendente some do template quando null (não deveria acontecer no fluxo normal,
    // mas cobre um cartão movido para AGUARDANDO_ENTREVISTA por fora da regra, ex.: SQL manual).
    public record ItemAguardandoEntrevista(Assistido assistido, Avaliacao avaliacaoPendente) {}

    @GetMapping("/entrevistas")
    public String fila(Model model, @AuthenticationPrincipal UserDetails usuarioLogado) {
        List<Assistido> aguardandoAvaliacao =
                assistidoRepository.findByStatusCartaoAndAtivoTrueOrderByNomeAsc(CartaoStatus.AGUARDANDO_AVALIACAO);

        List<ItemAguardandoEntrevista> aguardandoEntrevista =
                assistidoRepository.findByStatusCartaoAndAtivoTrueOrderByNomeAsc(CartaoStatus.AGUARDANDO_ENTREVISTA)
                        .stream()
                        .map(assistido -> new ItemAguardandoEntrevista(assistido,
                                avaliacaoRepository.findFirstByAssistidoIdAndEntrevistaIsNullOrderByDataDesc(assistido.getId())
                                        .orElse(null)))
                        .toList();

        model.addAttribute("aguardandoAvaliacao", aguardandoAvaliacao);
        model.addAttribute("aguardandoEntrevista", aguardandoEntrevista);
        model.addAttribute("meuId", meuId(usuarioLogado));
        return "entrevista-fila";
    }

    private Long meuId(UserDetails usuarioLogado) {
        if (usuarioLogado == null) {
            return null;
        }
        return assistidoRepository.findByLoginAndAcessoAtivoTrue(usuarioLogado.getUsername())
                .map(Assistido::getId)
                .orElse(null);
    }
}
