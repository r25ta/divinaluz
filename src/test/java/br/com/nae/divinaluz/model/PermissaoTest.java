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
 */
class PermissaoTest {

    @Test
    void dirigenteAlcancaTodosOsModulos() {
        assertEquals(EnumSet.allOf(Permissao.class), TipoTrabalhador.DIRIGENTE.getPermissoes());
    }

    @Test
    void recepcionistaCadastraEAtendeNaSessaoMasNaoEntrevista() {
        assertEquals(EnumSet.of(Permissao.CONSULTA, Permissao.CADASTRO, Permissao.SESSAO),
                TipoTrabalhador.RECEPCIONISTA.getPermissoes());
    }

    @Test
    void entrevistadorConduzEntrevistaMasNaoMexeNoCadastro() {
        assertEquals(EnumSet.of(Permissao.CONSULTA, Permissao.SESSAO, Permissao.ENTREVISTA),
                TipoTrabalhador.ENTREVISTADOR.getPermissoes());
    }

    @Test
    void secretariaMontaAEscalaDePrelecoes() {
        assertEquals(EnumSet.of(Permissao.CONSULTA, Permissao.CADASTRO, Permissao.SESSAO, Permissao.PRELECAO),
                TipoTrabalhador.SECRETARIA.getPermissoes());
    }

    @Test
    void preletorSoAlcancaAEscalaEEnaoConsultaProntuarios() {
        assertEquals(EnumSet.of(Permissao.PRELECAO), TipoTrabalhador.EXPOSITOR_PRELETOR.getPermissoes());
    }

    @Test
    void passistaEFacilitadorSomenteConsultam() {
        assertEquals(EnumSet.of(Permissao.CONSULTA), TipoTrabalhador.PASSISTA.getPermissoes());
        assertEquals(EnumSet.of(Permissao.CONSULTA), TipoTrabalhador.FACILITADOR.getPermissoes());
    }

    // Fora do fluxo de assistência espiritual: entra no sistema, mas só alcança o próprio cartão e a
    // escala de preleções (ambos liberados a qualquer autenticado no SecurityConfig).
    @Test
    void educadorDeEvangelizacaoNaoRecebeNenhumaPermissao() {
        assertTrue(TipoTrabalhador.EDUCADOR_EVANGELIZACAO.getPermissoes().isEmpty());
    }

    @Test
    void somenteODirigenteAlcancaOModuloDeTrabalhadores() {
        List<TipoTrabalhador> comAcesso = EnumSet.allOf(TipoTrabalhador.class).stream()
                .filter(funcao -> funcao.getPermissoes().contains(Permissao.TRABALHADORES))
                .toList();
        assertEquals(List.of(TipoTrabalhador.DIRIGENTE), comAcesso);
    }

    @Test
    void acumularFuncoesSomaOsAcessos() {
        Set<Permissao> permissoes = Permissao.de(
                List.of(TipoTrabalhador.EXPOSITOR_PRELETOR, TipoTrabalhador.ENTREVISTADOR));

        assertEquals(EnumSet.of(Permissao.CONSULTA, Permissao.SESSAO, Permissao.ENTREVISTA, Permissao.PRELECAO),
                permissoes);
    }

    @Test
    void semFuncaoNaoHaPermissaoAlguma() {
        assertTrue(Permissao.de(null).isEmpty());
        assertTrue(Permissao.de(List.of()).isEmpty());
        assertTrue(Permissao.de(List.of(TipoTrabalhador.EDUCADOR_EVANGELIZACAO)).isEmpty());
    }

    // O prefixo separa a permissão (o que a pessoa alcança) do ROLE_ do PerfilAcesso (se é staff).
    @Test
    void authorityUsaOPrefixoPerm() {
        assertEquals("PERM_CONSULTA", Permissao.CONSULTA.getAuthority());
        assertEquals("PERM_TRABALHADORES", Permissao.TRABALHADORES.getAuthority());
        assertFalse(Permissao.CONSULTA.getAuthority().startsWith("ROLE_"));
    }
}
