package br.com.nae.divinaluz.controller;

import br.com.nae.divinaluz.service.AuditoriaService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

/**
 * Módulo de Auditoria (2026-10-12): quem incluiu, alterou ou excluiu o quê, quando, e o valor
 * anterior e o novo. Só o Administrador, por enquanto (SecurityConfig).
 */
@Controller
public class AuditoriaController {

    private final AuditoriaService auditoriaService;

    public AuditoriaController(AuditoriaService auditoriaService) {
        this.auditoriaService = auditoriaService;
    }

    @GetMapping("/auditoria")
    public String listar(@RequestParam(required = false) @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate ate,
            @RequestParam(required = false) String usuario,
            @RequestParam(required = false) String acao,
            @RequestParam(required = false) String entidade,
            @RequestParam(required = false) String busca,
            @RequestParam(required = false) Long assistidoId,
            @RequestParam(defaultValue = "1") int pagina,
            Model model) {
        AuditoriaService.Filtro filtro = new AuditoriaService.Filtro(de, ate, usuario, acao, entidade, busca, assistidoId);
        model.addAttribute("filtro", filtro);
        model.addAttribute("resultado", auditoriaService.listar(filtro, pagina));
        model.addAttribute("entidades", auditoriaService.entidades());
        model.addAttribute("espaco", auditoriaService.espaco());
        return "auditoria";
    }
}
