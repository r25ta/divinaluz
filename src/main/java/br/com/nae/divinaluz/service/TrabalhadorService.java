package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.config.AuditoriaListener;
import br.com.nae.divinaluz.model.Assistido;
import br.com.nae.divinaluz.model.PerfilAcesso;
import br.com.nae.divinaluz.model.TipoTrabalhador;
import br.com.nae.divinaluz.model.Trabalhador;
import br.com.nae.divinaluz.repository.AssistidoRepository;
import br.com.nae.divinaluz.repository.TrabalhadorRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Perfis de trabalho: todo trabalhador é, antes, um assistido — não existe cadastro de trabalhador
 * do zero. Pelo prontuário ("Perfis de Trabalho") um assistido <em>evolui</em> para trabalhador
 * ganhando um ou mais perfis ({@link TipoTrabalhador}), e pode voltar a ser só assistido.
 */
@Service
public class TrabalhadorService {

    public static final String VINCULO_ASSISTIDO = "ASSISTIDO";
    public static final String VINCULO_TRABALHADOR = "TRABALHADOR";

    private final AssistidoRepository assistidoRepository;
    private final TrabalhadorRepository trabalhadorRepository;
    private final EntityManager entityManager;

    public TrabalhadorService(AssistidoRepository assistidoRepository,
            TrabalhadorRepository trabalhadorRepository, EntityManager entityManager) {
        this.assistidoRepository = assistidoRepository;
        this.trabalhadorRepository = trabalhadorRepository;
        this.entityManager = entityManager;
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
        String funcoesAntes = descreverFuncoes(trabalhador.getFuncoes());
        trabalhador.setFuncoes(ehTrabalhador ? new LinkedHashSet<>(perfis) : new LinkedHashSet<>());
        trabalhador = trabalhadorRepository.save(trabalhador);
        // A auditoria escuta as entidades, não as coleções: as funções são registradas aqui.
        AuditoriaListener.registrarAlteracao(entityManager.unwrap(Session.class), trabalhador,
                trabalhador.getId(), "funcoes", funcoesAntes, descreverFuncoes(trabalhador.getFuncoes()));

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

    private static String descreverFuncoes(Collection<TipoTrabalhador> funcoes) {
        if (funcoes == null || funcoes.isEmpty()) {
            return null;
        }
        return funcoes.stream().map(TipoTrabalhador::getLabel).sorted().collect(Collectors.joining(", "));
    }
}
