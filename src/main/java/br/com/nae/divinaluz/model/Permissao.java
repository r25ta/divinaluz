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

    /** Cadastrar, editar, desativar/reativar um assistido, mudar dia/tratamento e criar o acesso. */
    CADASTRO,

    /** Painel da sessão: check-in, escala, recepção, cadastro rápido e marcação de presença. */
    SESSAO,

    /** Fila de cartões retidos e registro de Avaliação/Entrevista. */
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
