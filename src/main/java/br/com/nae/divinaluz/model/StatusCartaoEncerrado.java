package br.com.nae.divinaluz.model;

/**
 * Como um ciclo de tratamento (cartão) terminou — ver {@link CartaoEncerrado}. É diferente de
 * {@link CartaoStatus}, que descreve o cartão <em>em andamento</em>: aqui o ciclo já fechou e o
 * valor não muda mais.
 */
public enum StatusCartaoEncerrado {

    /** Cumpriu as 4 sessões e passou por Avaliação + Entrevista, que abriram o ciclo seguinte. */
    CONCLUIDO("Tratamento Concluído", "dl-badge-success", "bi-check-circle-fill"),

    /** Expirou por 3 semanas sem presença efetiva (regra dos 21 dias) e foi reiniciado em P2. */
    INCOMPLETO_POR_TEMPO("Tratamento Incompleto", "dl-badge-danger", "bi-exclamation-triangle-fill"),

    /** O tratamento foi trocado pela recepção/edição antes de o ciclo terminar. */
    INTERROMPIDO("Interrompido por Troca de Tratamento", "dl-badge-neutral", "bi-arrow-repeat");

    private final String label;
    private final String badgeClasse;
    private final String icone;

    StatusCartaoEncerrado(String label, String badgeClasse, String icone) {
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
