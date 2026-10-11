package br.com.nae.divinaluz.model;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * O que um trabalhador pode alcançar no sistema. Cada {@link TipoTrabalhador} carrega as suas, e a
 * pessoa que acumula funções fica com a união delas ({@link #de(Collection)}).
 *
 * <p>Vira {@code GrantedAuthority} no login (ver {@code SecurityConfig}) com o prefixo
 * {@code PERM_}, separado do {@code ROLE_} do {@link PerfilAcesso}: o perfil diz <em>se</em> a
 * pessoa é staff, a permissão diz <em>o que</em> dela ela alcança. O Administrador recebe todas.
 */
public enum Permissao {

    /** Abrir a listagem de assistidos, o prontuário e o cartão de terceiros. */
    CONSULTA,

    /**
     * Consulta restrita do Avaliador (2026-10-11): listagem, prontuário e cartão de terceiros só
     * enquanto o cartão está "Em Avaliação". Registrada a avaliação, o cartão passa a Aguardando
     * Entrevista e sai da visão dele. Quem tem {@link #CONSULTA} já vê tudo.
     */
    CONSULTA_EM_AVALIACAO,

    /**
     * Consulta do Entrevistador (2026-10-11): listagem, prontuário e cartão de todos, <em>exceto</em>
     * quem está Em Avaliação — esse cartão ainda é do Avaliador. O próprio registro com o cartão
     * retido continua fora, pela regra de que ninguém conduz o próprio tratamento.
     */
    CONSULTA_EXCETO_EM_AVALIACAO,

    /**
     * O próprio prontuário (2026-10-11: Passista, Avaliador e Entrevistador). Todo mundo já alcança o
     * próprio cartão; isto abre também o prontuário — que, com o próprio cartão retido, continua
     * levando ao cartão.
     */
    PRONTUARIO_PROPRIO,

    /**
     * Dados cadastrais: cadastrar, editar os dados pessoais, desativar/reativar um assistido e criar
     * ou editar o login dele. <strong>Não</strong> inclui mexer no tratamento (ver {@link #PRONTUARIO}).
     */
    CADASTRO,

    /**
     * Dados do prontuário (2026-10-04): trocar o tratamento e o dia de assistência pelo prontuário.
     * Separado de {@link #CADASTRO} porque a Recepcionista cadastra e edita dados pessoais, mas não
     * altera o prontuário. O tratamento novo do ciclo normal vem da Avaliação/Entrevista.
     */
    PRONTUARIO,

    /** Painel da sessão: check-in, escala, recepção, cadastro rápido e marcação de presença. */
    SESSAO,

    /**
     * Registrar a Avaliação (2026-10-04, antes parte de {@link #ENTREVISTA}) e propor nela o novo
     * tratamento. Quem tem esta ou {@link #ENTREVISTA} vê a fila de cartões retidos e o tratamento
     * proposto enquanto o cartão aguarda a entrevista.
     */
    AVALIACAO,

    /**
     * Registrar a Entrevista: comunica ao assistido o tratamento proposto na Avaliação e devolve o
     * cartão para "Em Tratamento".
     */
    ENTREVISTA,

    /** Montar a escala de preleções (ver a escala é liberado a todos). */
    PRELECAO,

    /** Módulo "Cadastrar Trabalhador": promover um assistido e definir perfis de trabalho. */
    TRABALHADORES;

    public static final String PREFIXO_AUTHORITY = "PERM_";

    public String getAuthority() {
        return PREFIXO_AUTHORITY + name();
    }

    /** União das permissões das funções recebidas — quem acumula funções soma os acessos. */
    public static Set<Permissao> de(Collection<TipoTrabalhador> funcoes) {
        Set<Permissao> permissoes = EnumSet.noneOf(Permissao.class);
        if (funcoes != null) {
            funcoes.stream().filter(java.util.Objects::nonNull)
                    .forEach(funcao -> permissoes.addAll(funcao.getPermissoes()));
        }
        return permissoes;
    }
}
