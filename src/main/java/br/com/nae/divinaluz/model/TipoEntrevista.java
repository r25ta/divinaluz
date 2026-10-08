package br.com.nae.divinaluz.model;

/**
 * Os três tipos de entrevista do prontuário (V43, 2026-10-08 — ver CLAUDE.md 3.25). Só a entrevista
 * de {@link #TRATAMENTO} segue uma Avaliação, decide o tratamento das próximas 4 sessões (ou a alta) e
 * libera o cartão retido; as outras duas ficam registradas no prontuário sem mexer no cartão.
 */
public enum TipoEntrevista {

    /** Depois da Avaliação: comunica o novo tratamento ou a alta e abre o ciclo seguinte. */
    TRATAMENTO("Entrevista do tratamento"),

    /**
     * No dia da 1ª sessão de um tratamento (cadastro novo, reinício em P2 ou volta depois da alta), se
     * o assistido quiser. Acolhe e orienta; o tratamento continua o que a 1ª sessão abriu.
     */
    PRIMEIRA_SESSAO("Entrevista da 1ª sessão"),

    /**
     * Pedida pelo assistido a qualquer momento, sem ligação direta com o tratamento. Não substitui
     * nem libera a entrevista do tratamento.
     */
    EXCEPCIONAL("Entrevista excepcional");

    private final String label;

    TipoEntrevista(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** As que não seguem uma Avaliação e não mexem no cartão. */
    public boolean isAvulsa() {
        return this != TRATAMENTO;
    }
}
