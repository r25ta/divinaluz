package br.com.nae.divinaluz.model;

/**
 * Posições da escala de uma sessão de assistência (protótipo do item 6). São independentes das
 * funções do perfil de trabalhador ({@link TipoTrabalhador}): qualquer trabalhador pode ser escalado
 * em qualquer posição, e o mínimo serve só de aviso na tela — nunca bloqueia a sessão.
 * {@code minimo = 0} significa que a posição não tem mínimo definido.
 */
public enum PosicaoSessao {

    DIRIGENTE("Dirigente", 1, "bi-person-badge", "Responsável pela gestão da sessão."),
    RECEPCIONISTA("Recepcionista", 2, "bi-door-open", "Recebe os assistidos e encaminha para entrevista."),
    SECRETARIA("Secretária", 1, "bi-journal-text", "Apoio de secretaria da sessão."),
    ENTREVISTADOR("Entrevistador", 1, "bi-chat-left-text", "Entrevista assistidos novos e os que passaram por avaliação."),
    PASSE_LIMPEZA("Passe de Limpeza", 1, "bi-droplet", "Passistas do passe de limpeza."),
    // A câmara de passe reúne 5 pessoas no mínimo: 1 dirigente + 4 passistas (decisão de
    // 2026-09-28 — antes o mínimo de 5 estava todo em CAMARA_PASSE, com o dirigente sem mínimo).
    CAMARA_PASSE("Câmara de Passe", 4, "bi-stars", "Passistas dos tratamentos P1, CH e P2."),
    DIRIGENTE_CAMARA("Dirigente da Câmara de Passe", 1, "bi-person-gear", "Conduz a câmara de passe."),
    P3B("P3B", 3, "bi-heart-pulse", "Passistas do tratamento P3B.");

    private final String label;
    private final int minimo;
    private final String icone;
    private final String descricao;

    PosicaoSessao(String label, int minimo, String icone, String descricao) {
        this.label = label;
        this.minimo = minimo;
        this.icone = icone;
        this.descricao = descricao;
    }

    public String getLabel() {
        return label;
    }

    public int getMinimo() {
        return minimo;
    }

    public String getIcone() {
        return icone;
    }

    public String getDescricao() {
        return descricao;
    }
}
