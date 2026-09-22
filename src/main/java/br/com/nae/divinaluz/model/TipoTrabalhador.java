package br.com.nae.divinaluz.model;

public enum TipoTrabalhador {

    DIRIGENTE(
            "Dirigente",
            "Responsável pela condução das reuniões gerais, turmas de estudo, preleções e orientação doutrinária das atividades e da casa espírita."),
    EXPOSITOR_PRELETOR(
            "Expositor / Preletor",
            "Membro encarregado de transmitir os ensinamentos evangélico-doutrinário nas palestras públicas e preleções que antecedem a assistência espiritual."),
    PASSISTA(
            "Passista",
            "Voluntário preparado para o atendimento fraterno, aplicação de passes magnéticos e amparo aos frequentadores."),
    FACILITADOR(
            "Facilitador",
            "Atua orientando os grupos de aprofundamento íntimo e vivência."),
    EDUCADOR_EVANGELIZACAO(
            "Educador de Evangelização Infantil e Mocidade",
            "Responsável pelo ensino e moralização cristã voltada para crianças e jovens.");

    private final String label;
    private final String descricao;

    TipoTrabalhador(String label, String descricao) {
        this.label = label;
        this.descricao = descricao;
    }

    public String getLabel() {
        return label;
    }

    public String getDescricao() {
        return descricao;
    }
}
