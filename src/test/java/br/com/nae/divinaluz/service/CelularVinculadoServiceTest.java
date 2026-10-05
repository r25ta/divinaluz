package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.exception.RegraNegocioException;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.CelularVinculado;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.CelularVinculadoRepository;
import br.com.nae.divinaluz.service.CelularVinculadoService.PessoaDoCelular;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CelularVinculadoServiceTest {

    @Mock
    private CelularVinculadoRepository celularVinculadoRepository;

    @Mock
    private AssistidoRepository assistidoRepository;

    private CelularVinculadoService service;

    /** "Banco" dos vínculos, por hash do token — o repositório de mentira grava e acha aqui. */
    private final Map<String, CelularVinculado> vinculos = new HashMap<>();

    private Assistido maria;
    private Assistido joao;

    @BeforeEach
    void preparar() {
        service = new CelularVinculadoService(celularVinculadoRepository, assistidoRepository);
        maria = pessoa(7L, "Maria das Dores");
        joao = pessoa(8L, "João da Silva");
        lenient().when(celularVinculadoRepository.save(any())).thenAnswer(invocacao -> {
            CelularVinculado celular = invocacao.getArgument(0);
            vinculos.put(celular.getTokenHash(), celular);
            return celular;
        });
        lenient().when(celularVinculadoRepository.findByTokenHash(anyString()))
                .thenAnswer(invocacao -> Optional.ofNullable(vinculos.get(invocacao.<String>getArgument(0))));
        lenient().doAnswer(invocacao -> vinculos.values().remove(invocacao.<CelularVinculado>getArgument(0)))
                .when(celularVinculadoRepository).delete(any());
    }

    private static Assistido pessoa(Long id, String nome) {
        Assistido assistido = new Assistido();
        assistido.setId(id);
        assistido.setNome(nome);
        return assistido;
    }

    @Test
    void vincularGuardaSoOHashEOTokenIdentificaADona() {
        List<String> tokens = service.vincular(List.of(), maria);

        assertEquals(1, tokens.size());
        String token = tokens.get(0);
        assertTrue(token.length() >= 40, "token de 256 bits em Base64: " + token);
        assertFalse(vinculos.containsKey(token), "o token não pode ser gravado em texto puro");
        assertTrue(vinculos.containsKey(CelularVinculadoService.hash(token)));

        List<PessoaDoCelular> doCelular = service.identificar(tokens);
        assertEquals(1, doCelular.size());
        assertEquals(maria, doCelular.get(0).assistido());
        assertTrue(vinculos.get(CelularVinculadoService.hash(token)).getUltimoUsoEm() != null);
    }

    @Test
    void tokenDesconhecidoOuDeCadastroDesativadoNaoIdentificaNinguem() {
        List<String> tokens = service.vincular(List.of(), maria);
        assertTrue(service.identificar(List.of("token-que-nao-existe")).isEmpty());

        maria.setAtivo(false);
        assertTrue(service.identificar(tokens).isEmpty(), "cadastro desativado não marca presença pelo celular");
    }

    @Test
    void familiaDivideOCelularECadaUmTemOSeuToken() {
        List<String> soMaria = service.vincular(List.of(), maria);
        List<String> mariaEJoao = service.vincular(service.identificar(soMaria), joao);

        assertEquals(2, mariaEJoao.size());
        List<PessoaDoCelular> doCelular = service.identificar(mariaEJoao);
        assertEquals(List.of(maria, joao), doCelular.stream().map(PessoaDoCelular::assistido).toList());
    }

    @Test
    void vincularDeNovoAMesmaPessoaTrocaOTokenEmVezDeAcumular() {
        List<String> primeiro = service.vincular(List.of(), maria);
        List<String> segundo = service.vincular(service.identificar(primeiro), maria);

        assertEquals(1, segundo.size());
        assertNotEquals(primeiro.get(0), segundo.get(0));
        assertTrue(service.identificar(primeiro).isEmpty(), "o token antigo deixa de valer");
        assertEquals(1, vinculos.size());
    }

    @Test
    void celularTemLimiteDePessoas() {
        List<PessoaDoCelular> doCelular = new ArrayList<>();
        for (long i = 0; i < CelularVinculadoService.MAXIMO_DE_PESSOAS; i++) {
            doCelular.add(new PessoaDoCelular("t" + i, pessoa(100 + i, "Pessoa " + i)));
        }
        assertThrows(RegraNegocioException.class, () -> service.vincular(doCelular, maria));
    }

    @Test
    void desvincularTiraOToken() {
        List<String> tokens = service.vincular(List.of(), maria);
        service.desvincular(tokens.get(0));
        assertTrue(service.identificar(tokens).isEmpty());
    }

    @Test
    void conviteGuardaSoOHashEValeUmaVez() {
        String convite = service.gerarConvite(maria);

        assertNotEquals(convite, maria.getConviteCelularHash());
        assertEquals(CelularVinculadoService.hash(convite), maria.getConviteCelularHash());
        assertTrue(maria.getConviteCelularExpiraEm().isAfter(LocalDateTime.now().plusMinutes(9)));

        when(assistidoRepository.findByConviteCelularHash(maria.getConviteCelularHash()))
                .thenReturn(Optional.of(maria));
        List<String> tokens = service.aceitarConvite(convite, List.of());

        assertEquals(maria, service.identificar(tokens).get(0).assistido());
        assertNull(maria.getConviteCelularHash(), "o convite é de uso único");
        assertNull(maria.getConviteCelularExpiraEm());
    }

    @Test
    void conviteAcrescentaAPessoaAsQueJaUsamOCelular() {
        List<String> soJoao = service.vincular(List.of(), joao);
        String convite = service.gerarConvite(maria);
        when(assistidoRepository.findByConviteCelularHash(CelularVinculadoService.hash(convite)))
                .thenReturn(Optional.of(maria));

        List<String> tokens = service.aceitarConvite(convite, service.identificar(soJoao));

        assertEquals(List.of(joao, maria),
                service.identificar(tokens).stream().map(PessoaDoCelular::assistido).toList());
    }

    @Test
    void conviteExpiradoOuDesconhecidoEhRecusado() {
        assertThrows(RegraNegocioException.class, () -> service.conferirConvite("nao-existe"));
        assertThrows(RegraNegocioException.class, () -> service.conferirConvite(""));

        String convite = service.gerarConvite(maria);
        maria.setConviteCelularExpiraEm(LocalDateTime.now().minusSeconds(1));
        when(assistidoRepository.findByConviteCelularHash(CelularVinculadoService.hash(convite)))
                .thenReturn(Optional.of(maria));
        assertThrows(RegraNegocioException.class, () -> service.conferirConvite(convite));
    }

    @Test
    void conviteNaoEhGeradoParaCadastroDesativado() {
        maria.setAtivo(false);
        assertThrows(RegraNegocioException.class, () -> service.gerarConvite(maria));
    }

    @Test
    void novoConviteSubstituiOAnterior() {
        String primeiro = service.gerarConvite(maria);
        String segundo = service.gerarConvite(maria);

        assertNotEquals(primeiro, segundo);
        assertEquals(CelularVinculadoService.hash(segundo), maria.getConviteCelularHash());
        ArgumentCaptor<Assistido> salvo = ArgumentCaptor.forClass(Assistido.class);
        verify(assistidoRepository, org.mockito.Mockito.times(2)).save(salvo.capture());
    }
}
