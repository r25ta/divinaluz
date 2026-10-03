package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.service.AcessoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consulta que o formulário de cadastro faz enquanto a recepção digita (form.html): o login
 * sugerido para o nome, se o login digitado está livre e se o e-mail já está em outro cadastro.
 * É só conforto — o {@code ProntuarioController} valida tudo de novo ao salvar.
 *
 * <p>Exige a permissão {@code CADASTRO} (ver SecurityConfig): diz de quem é um e-mail, o que só faz
 * sentido para quem já está cadastrando pessoas. Para o público, a entrada por código continua sem
 * revelar se um e-mail é cadastrado.</p>
 */
@RestController
public class AcessoController {

    private final AcessoService acessoService;

    public AcessoController(AcessoService acessoService) {
        this.acessoService = acessoService;
    }

    @GetMapping("/acesso/verificar")
    public Map<String, Object> verificar(@RequestParam(required = false) String nome,
            @RequestParam(required = false) String login, @RequestParam(required = false) String email,
            @RequestParam(required = false) Long id) {
        AcessoService.Verificacao verificacao = acessoService.verificar(nome, login, email, id);

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("sugestao", verificacao.sugestao());
        resposta.put("loginProblema", verificacao.problemaLogin());
        Assistido dono = verificacao.donoDoEmail();
        if (dono != null) {
            Map<String, Object> donoEmail = new LinkedHashMap<>();
            donoEmail.put("id", dono.getId());
            donoEmail.put("nome", dono.getNome());
            donoEmail.put("ativo", dono.isAtivo());
            resposta.put("emailEmUso", donoEmail);
        }
        return resposta;
    }
}
