package br.com.nae.divinaluz.model;

public enum CartaoStatus {
    EM_TRATAMENTO("Em Tratamento", "dl-badge-success", "bi-heart-pulse-fill"),
    // "Em Avaliação" desde 2026-10-07 (o nome do valor ficou, porque é o que está gravado no banco):
    // o cartão completou as 4 sessões e está com o Avaliador.
    AGUARDANDO_AVALIACAO("Em Avaliação", "dl-badge-warning", "bi-hourglass-split"),
    AGUARDANDO_ENTREVISTA("Aguardando Entrevista", "dl-badge-info", "bi-chat-left-text-fill"),
    INCOMPLETO_POR_TEMPO("Incompleto por Tempo", "dl-badge-danger", "bi-exclamation-triangle-fill"),
    // Alta comunicada na entrevista (2026-10-07): sem tratamento em andamento. Se a pessoa voltar,
    // entra como ouvinte; um novo tratamento (P2) só pela recepção.
    ALTA("Alta", "dl-badge-teal", "bi-award-fill");

    private final String label;
    private final String badgeClasse;
    private final String icone;

    CartaoStatus(String label, String badgeClasse, String icone) {
        this.label = label;
        this.badgeClasse = badgeClasse;
        this.icone = icone;
    }

    public String getLabel() {
        return label;
    }

    public String getBadgeClasse() {
        return badgeClasse;
    }

    public String getIcone() {
        return icone;
    }

    /** Cartão em posse da casa (Em Avaliação / Aguardando Entrevista): não recebe presença nem datas. */
    public boolean isRetido() {
        return this == AGUARDANDO_AVALIACAO || this == AGUARDANDO_ENTREVISTA;
    }
}
