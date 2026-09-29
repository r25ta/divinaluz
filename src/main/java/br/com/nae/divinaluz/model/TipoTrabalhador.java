package br.com.nae.divinaluz.model;

/**
 * Perfil de trabalho (função permanente na casa) de um {@link Trabalhador}. É o que define — ou
 * vai definir — a visibilidade de cada trabalhador no sistema, e é escolhido no módulo "Cadastrar
 * Trabalhador" ao promover um assistido.
 *
 * <p>Não confundir com {@link PosicaoSessao}, que é a escala de <em>uma</em> sessão (quem trabalha
 * neste domingo/terça): a pessoa pode ser Passista aqui e ser escalada na Câmara de Passe lá.
 */
public enum TipoTrabalhador {

    DIRIGENTE(
            "Dirigente",
            "Responsável pela condução das reuniões gerais, turmas de estudo, preleções e orientação doutrinária das atividades e da casa espírita."),
    RECEPCIONISTA(
            "Recepcionista",
            "Recebe o assistido na chegada, consulta o cadastro, marca a presença na sessão e encaminha para a entrevista."),
    ENTREVISTADOR(
            "Entrevistador",
            "Entrevista os assistidos novos e os que passaram por avaliação espiritual, comunicando as orientações e o tratamento indicado."),
    SECRETARIA(
            "Secretária",
            "Apoio de secretaria da sessão e dos registros da casa."),
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
