package br.com.nae.divinaluz.model;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A matriz de visibilidade por função — o que cada perfil de trabalho enxerga. Está em teste porque
 * é uma decisão de negócio da casa (não uma consequência do código): mudá-la deve ser deliberado.
 * Redefinida pelo responsável do projeto em 2026-10-04.
 */
class PermissaoTest {

    @Test
    void catalogoDeFuncoesRedefinidoEm20261004() {
        assertEquals(List.of(TipoTrabalhador.DIRIGENTE, TipoTrabalhador.RECEPCIONISTA, TipoTrabalhador.AVALIADOR,
                        TipoTrabalhador.ENTREVISTADOR, TipoTrabalhador.EXPOSITOR_PRELETOR, TipoTrabalhador.PASSISTA),
                List.of(TipoTrabalhador.values()));
    }

    @Test
    void dirigenteAlcancaTodosOsModulos() {
        assertEquals(EnumSet.allOf(Permissao.class), TipoTrabalhador.DIRIGENTE.getPermissoes());
    }

    // Sessão, consulta, cadastro (dados cadastrais, acesso, celular, desativar) e escala de preleções —
    // mas não altera o prontuário (tratamento/dia), não registra avaliação ou entrevista e não define
    // perfis de trabalho (só o Dirigente, confirmado em 2026-10-12).
    @Test
    void recepcionistaCadastraAtendeNaSessaoEMontaPrelecoesMasNaoAlteraOProntuario() {
        assertEquals(EnumSet.of(Permissao.CONSULTA, Permissao.CADASTRO, Permissao.SESSAO, Permissao.PRELECAO),
                TipoTrabalhador.RECEPCIONISTA.getPermissoes());
        assertFalse(TipoTrabalhador.RECEPCIONISTA.getPermissoes().contains(Permissao.PRONTUARIO));
        assertFalse(TipoTrabalhador.RECEPCIONISTA.getPermissoes().contains(Permissao.AVALIACAO));
        assertFalse(TipoTrabalhador.RECEPCIONISTA.getPermissoes().contains(Permissao.ENTREVISTA));
    }

    @Test
    void avaliadorRegistraAAvaliacaoEConsultaSoOsCartoesEmAvaliacao() {
        assertEquals(EnumSet.of(Permissao.CONSULTA_EM_AVALIACAO, Permissao.AVALIACAO, Permissao.PRONTUARIO_PROPRIO),
                TipoTrabalhador.AVALIADOR.getPermissoes());
    }

    @Test
    void entrevistadorComunicaOTratamentoEConsultaTodosMenosOsEmAvaliacao() {
        assertEquals(EnumSet.of(Permissao.CONSULTA_EXCETO_EM_AVALIACAO, Permissao.ENTREVISTA, Permissao.PRONTUARIO_PROPRIO),
                TipoTrabalhador.ENTREVISTADOR.getPermissoes());
    }

    // 2026-10-11: a consulta completa ficou com quem atende na recepção e com o Dirigente.
    @Test
    void soDirigenteERecepcionistaConsultamTodos() {
        List<TipoTrabalhador> consultam = EnumSet.allOf(TipoTrabalhador.class).stream()
                .filter(f -> f.getPermissoes().contains(Permissao.CONSULTA))
                .toList();
        assertEquals(List.of(TipoTrabalhador.DIRIGENTE, TipoTrabalhador.RECEPCIONISTA), consultam);
    }

    @Test
    void preletorSoAlcancaAEscalaEEnaoConsultaProntuarios() {
        assertEquals(EnumSet.of(Permissao.PRELECAO), TipoTrabalhador.EXPOSITOR_PRELETOR.getPermissoes());
    }

    // 2026-10-12: o Passista não vê prontuário nenhum, nem o próprio — só o próprio cartão, que é de todos.
    @Test
    void passistaNaoTemPermissaoNenhuma() {
        assertTrue(TipoTrabalhador.PASSISTA.getPermissoes().isEmpty());
    }

    // Avaliador + Passista: a visão é a do Avaliador, sem ganhar a consulta completa.
    @Test
    void avaliadorEPassistaNaoConsultamTodos() {
        assertEquals(EnumSet.of(Permissao.CONSULTA_EM_AVALIACAO, Permissao.AVALIACAO, Permissao.PRONTUARIO_PROPRIO),
                Permissao.de(List.of(TipoTrabalhador.AVALIADOR, TipoTrabalhador.PASSISTA)));
    }

    // O tratamento proposto, enquanto o cartão aguarda a entrevista, só é visto por Avaliador,
    // Entrevistador e Dirigente — quem tem AVALIACAO ou ENTREVISTA.
    @Test
    void soAvaliadorEntrevistadorEDirigenteVeemOTratamentoProposto() {
        List<TipoTrabalhador> veem = EnumSet.allOf(TipoTrabalhador.class).stream()
                .filter(f -> f.getPermissoes().contains(Permissao.AVALIACAO) || f.getPermissoes().contains(Permissao.ENTREVISTA))
                .toList();
        assertEquals(List.of(TipoTrabalhador.DIRIGENTE, TipoTrabalhador.AVALIADOR, TipoTrabalhador.ENTREVISTADOR), veem);
    }

    // Promover a trabalhador e mexer nos perfis é só do Dirigente (reconfirmado em 2026-10-12).
    @Test
    void somenteODirigenteAlteraOProntuarioEPromoveTrabalhadores() {
        for (Permissao exclusiva : List.of(Permissao.PRONTUARIO, Permissao.TRABALHADORES)) {
            List<TipoTrabalhador> comAcesso = EnumSet.allOf(TipoTrabalhador.class).stream()
                    .filter(funcao -> funcao.getPermissoes().contains(exclusiva))
                    .toList();
            assertEquals(List.of(TipoTrabalhador.DIRIGENTE), comAcesso, exclusiva.name());
        }
    }

    // Preletor e Passista não veem prontuário nenhum (2026-10-12) — a não ser que acumulem outra função.
    @Test
    void preletorEPassistaNaoVeemProntuario() {
        Set<Permissao> deProntuario = EnumSet.of(Permissao.CONSULTA, Permissao.CONSULTA_EM_AVALIACAO,
                Permissao.CONSULTA_EXCETO_EM_AVALIACAO, Permissao.PRONTUARIO_PROPRIO);
        Set<Permissao> juntos = Permissao.de(List.of(TipoTrabalhador.EXPOSITOR_PRELETOR, TipoTrabalhador.PASSISTA));
        assertTrue(juntos.stream().noneMatch(deProntuario::contains));
    }

    @Test
    void acumularFuncoesSomaOsAcessos() {
        Set<Permissao> permissoes = Permissao.de(
                List.of(TipoTrabalhador.EXPOSITOR_PRELETOR, TipoTrabalhador.ENTREVISTADOR));

        assertEquals(EnumSet.of(Permissao.CONSULTA_EXCETO_EM_AVALIACAO, Permissao.ENTREVISTA,
                Permissao.PRONTUARIO_PROPRIO, Permissao.PRELECAO), permissoes);
    }

    @Test
    void semFuncaoNaoHaPermissaoAlguma() {
        assertTrue(Permissao.de(null).isEmpty());
        assertTrue(Permissao.de(List.of()).isEmpty());
    }

    // O prefixo separa a permissão (o que a pessoa alcança) do ROLE_ do PerfilAcesso (se é staff).
    @Test
    void authorityUsaOPrefixoPerm() {
        assertEquals("PERM_CONSULTA", Permissao.CONSULTA.getAuthority());
        assertEquals("PERM_TRABALHADORES", Permissao.TRABALHADORES.getAuthority());
        assertFalse(Permissao.CONSULTA.getAuthority().startsWith("ROLE_"));
    }
}
