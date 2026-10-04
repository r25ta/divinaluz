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
 * <p>Quem acumula funções soma os acessos, e todo trabalhador — mesmo o que tem poucas permissões,
 * como o Expositor/Preletor — sempre alcança o próprio cartão e a escala de preleções.
 *
 * <p><strong>Catálogo redefinido em 2026-10-04</strong> pelo responsável do projeto: entrou o
 * Avaliador; saíram Secretária (quem tinha virou Recepcionista), Facilitador e Educador de
 * Evangelização Infantil e Mocidade (V36). Como o {@code @ElementCollection} grava o {@code name()},
 * <em>remover</em> um valor exige migration — uma linha com nome desconhecido quebraria a leitura do
 * trabalhador inteiro.
 */
public enum TipoTrabalhador {

    DIRIGENTE(
            "Dirigente",
            "Responsável pela condução das reuniões gerais, turmas de estudo, preleções e orientação doutrinária das atividades e da casa espírita. Acesso total ao sistema.",
            EnumSet.allOf(Permissao.class)),
    RECEPCIONISTA(
            "Recepcionista",
            "Recebe o assistido na chegada, consulta e mantém os dados cadastrais e o login, marca a presença na sessão, cuida da escala de preleções e encaminha para a entrevista. Não altera o prontuário.",
            EnumSet.of(Permissao.CONSULTA, Permissao.CADASTRO, Permissao.SESSAO, Permissao.PRELECAO)),
    AVALIADOR(
            "Avaliador",
            "Faz a avaliação espiritual do cartão retido na 4ª sessão e propõe o novo tratamento, que o entrevistador comunica.",
            EnumSet.of(Permissao.CONSULTA, Permissao.AVALIACAO)),
    ENTREVISTADOR(
            "Entrevistador",
            "Comunica ao assistido o tratamento proposto na avaliação e devolve o cartão para Em Tratamento.",
            EnumSet.of(Permissao.CONSULTA, Permissao.ENTREVISTA)),
    EXPOSITOR_PRELETOR(
            "Expositor / Preletor",
            "Membro encarregado de transmitir os ensinamentos evangélico-doutrinário nas palestras públicas e preleções que antecedem a assistência espiritual.",
            EnumSet.of(Permissao.PRELECAO)),
    PASSISTA(
            "Passista",
            "Voluntário preparado para o atendimento fraterno, aplicação de passes magnéticos e amparo aos frequentadores.",
            EnumSet.of(Permissao.CONSULTA));

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
