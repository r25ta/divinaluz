package br.com.nae.divinaluz.model;

/**
 * O que a Avaliação decide (e a Entrevista comunica) ao fim de um cartão de 4 sessões: um novo
 * tratamento para as próximas 4 sessões, ou a alta. Ver CLAUDE.md 3.23.
 */
public enum ResultadoAvaliacao {
    NOVO_TRATAMENTO("Novo tratamento"),
    ALTA("Alta");

    private final String label;

    ResultadoAvaliacao(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
