package br.com.nae.divinaluz.model;

public enum CartaoStatus {
    EM_TRATAMENTO("Em Tratamento"),
    AGUARDANDO_AVALIACAO("Aguardando Avaliação"),
    AGUARDANDO_ENTREVISTA("Aguardando Entrevista"),
    INCOMPLETO_POR_TEMPO("Incompleto por Tempo");

    private final String label;

    CartaoStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}