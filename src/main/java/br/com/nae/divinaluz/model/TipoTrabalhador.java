package br.com.nae.divinaluz.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Perfil de trabalho (função permanente na casa) de um {@link Trabalhador}. É o que define a
 * visibilidade de cada trabalhador no sistema — cada função carrega as suas {@link Permissao} —, e
 * é escolhido no módulo "Cadastrar Trabalhador" ao promover um assistido.
 *
 * <p>Não confundir com {@link PosicaoSessao}, que é a escala de <em>uma</em> sessão (quem trabalha
 * neste domingo/terça): a pessoa pode ser Passista aqui e ser escalada na Câmara de Passe lá.
 *
 * <p>Quem acumula funções soma os acessos, e todo trabalhador — mesmo sem nenhuma permissão, como o
 * Educador de Evangelização — sempre alcança o próprio cartão e a escala de preleções.
 */
public enum TipoTrabalhador {

    DIRIGENTE(
            "Dirigente",
            "Responsável pela condução das reuniões gerais, turmas de estudo, preleções e orientação doutrinária das atividades e da casa espírita.",
            EnumSet.allOf(Permissao.class)),
    RECEPCIONISTA(
            "Recepcionista",
            "Recebe o assistido na chegada, consulta o cadastro, marca a presença na sessão e encaminha para a entrevista.",
            EnumSet.of(Permissao.CONSULTA, Permissao.CADASTRO, Permissao.SESSAO)),
    ENTREVISTADOR(
            "Entrevistador",
            "Entrevista os assistidos novos e os que passaram por avaliação espiritual, comunicando as orientações e o tratamento indicado.",
            EnumSet.of(Permissao.CONSULTA, Permissao.SESSAO, Permissao.ENTREVISTA)),
    SECRETARIA(
            "Secretária",
            "Apoio de secretaria da sessão e dos registros da casa.",
            EnumSet.of(Permissao.CONSULTA, Permissao.CADASTRO, Permissao.SESSAO, Permissao.PRELECAO)),
    EXPOSITOR_PRELETOR(
            "Expositor / Preletor",
            "Membro encarregado de transmitir os ensinamentos evangélico-doutrinário nas palestras públicas e preleções que antecedem a assistência espiritual.",
            EnumSet.of(Permissao.PRELECAO)),
    PASSISTA(
            "Passista",
            "Voluntário preparado para o atendimento fraterno, aplicação de passes magnéticos e amparo aos frequentadores.",
            EnumSet.of(Permissao.CONSULTA)),
    FACILITADOR(
            "Facilitador",
            "Atua orientando os grupos de aprofundamento íntimo e vivência.",
            EnumSet.of(Permissao.CONSULTA)),
    EDUCADOR_EVANGELIZACAO(
            "Educador de Evangelização Infantil e Mocidade",
            "Responsável pelo ensino e moralização cristã voltada para crianças e jovens.",
            EnumSet.noneOf(Permissao.class));

    private final String label;
    private final String descricao;
    private final Set<Permissao> permissoes;

    TipoTrabalhador(String label, String descricao, Set<Permissao> permissoes) {
        this.label = label;
        this.descricao = descricao;
        this.permissoes = Collections.unmodifiableSet(permissoes);
    }

    public Set<Permissao> getPermissoes() {
        return permissoes;
    }

    public String getLabel() {
        return label;
    }

    public String getDescricao() {
        return descricao;
    }
}
