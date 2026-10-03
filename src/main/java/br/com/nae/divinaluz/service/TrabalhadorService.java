package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Módulo "Cadastrar Trabalhador": todo trabalhador é, antes, um assistido — não existe cadastro de
 * trabalhador do zero. Aqui um assistido já cadastrado <em>evolui</em> para trabalhador ganhando um
 * ou mais perfis de trabalho ({@link TipoTrabalhador}), e pode voltar a ser só assistido.
 */
@Service
public class TrabalhadorService {

    public static final String VINCULO_ASSISTIDO = "ASSISTIDO";
    public static final String VINCULO_TRABALHADOR = "TRABALHADOR";
    private static final int MAXIMO_RESULTADOS_BUSCA = 20;

    private final AssistidoRepository assistidoRepository;
    private final TrabalhadorRepository trabalhadorRepository;

    public TrabalhadorService(AssistidoRepository assistidoRepository,
            TrabalhadorRepository trabalhadorRepository) {
        this.assistidoRepository = assistidoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
    }

    /**
     * Define os perfis de trabalho do assistido. Com pelo menos um perfil ele passa a ter vínculo
     * TRABALHADOR; sem nenhum, volta a ser só assistido.
     *
     * <p>Na "despromoção" a linha de {@link Trabalhador} é mantida (apenas sem funções) de
     * propósito: ela é referenciada pelas preleções e pela escala das sessões, e apagá-la levaria
     * junto esse histórico (a FK de {@code prelecao} tem {@code ON DELETE CASCADE}).
     *
     * <p>O perfil do acesso acompanha (2026-10-03): quem tem login vira {@code TRABALHADOR} ao
     * ganhar uma função e volta a {@code ASSISTIDO} ao perder todas. O {@code ADMINISTRADOR} nunca é
     * mexido aqui — rebaixá-lo por desmarcar funções tiraria o acesso total por engano.</p>
     *
     * @return {@code true} se o perfil do acesso mudou (vale só no próximo login da pessoa)
     */
    @Transactional
    public boolean definirPerfis(Assistido assistido, Collection<TipoTrabalhador> perfis) {
        boolean ehTrabalhador = perfis != null && !perfis.isEmpty();

        Trabalhador trabalhador = trabalhadorRepository.findByAssistidoId(assistido.getId())
                .orElseGet(() -> {
                    Trabalhador novo = new Trabalhador();
                    novo.setAssistido(assistido);
                    return novo;
                });
        trabalhador.setFuncoes(ehTrabalhador ? new LinkedHashSet<>(perfis) : new LinkedHashSet<>());
        trabalhadorRepository.save(trabalhador);

        assistido.setVinculo(ehTrabalhador ? VINCULO_TRABALHADOR : VINCULO_ASSISTIDO);

        boolean perfilMudou = false;
        if (assistido.getLogin() != null && assistido.getPerfilAcesso() != PerfilAcesso.ADMINISTRADOR) {
            PerfilAcesso novoPerfil = ehTrabalhador ? PerfilAcesso.TRABALHADOR : PerfilAcesso.ASSISTIDO;
            perfilMudou = novoPerfil != assistido.getPerfilAcesso();
            assistido.setPerfilAcesso(novoPerfil);
        }
        assistidoRepository.save(assistido);
        return perfilMudou;
    }

    /** Trabalhadores de fato: quem tem pelo menos um perfil de trabalho, por nome. */
    public List<Trabalhador> listarTrabalhadores() {
        return trabalhadorRepository.findAll().stream()
                .filter(t -> t.getFuncoes() != null && !t.getFuncoes().isEmpty())
                .filter(t -> t.getAssistido() != null)
                .sorted(Comparator.comparing(t -> t.getAssistido().getNome(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Busca entre os assistidos ativos para escolher quem vai virar trabalhador. Sem acento, sem
     * diferenciar maiúsculas e pedindo ao menos 2 letras — mesmo critério da busca da recepção.
     */
    public List<Assistido> buscarAssistidos(String termo) {
        String busca = normalizar(termo);
        if (busca.length() < 2) {
            return List.of();
        }
        return assistidoRepository.findByAtivo(true).stream()
                .filter(a -> normalizar(a.getNome()).contains(busca))
                .sorted(Comparator.comparing(Assistido::getNome, String.CASE_INSENSITIVE_ORDER))
                .limit(MAXIMO_RESULTADOS_BUSCA)
                .toList();
    }

    private String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
