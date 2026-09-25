package br.com.nae.divinaluz.model;

public enum CartaoStatus {
    EM_TRATAMENTO("Em Tratamento", "dl-badge-success", "bi-heart-pulse-fill"),
    AGUARDANDO_AVALIACAO("Aguardando Avaliação", "dl-badge-warning", "bi-hourglass-split"),
    AGUARDANDO_ENTREVISTA("Aguardando Entrevista", "dl-badge-info", "bi-chat-left-text-fill"),
    INCOMPLETO_POR_TEMPO("Incompleto por Tempo", "dl-badge-danger", "bi-exclamation-triangle-fill");

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
}
