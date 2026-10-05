package br.com.nae.divinaluz.repository;

import br.com.nae.divinaluz.model.SessaoAssistencia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SessaoAssistenciaRepository extends JpaRepository<SessaoAssistencia, Long> {
    Optional<SessaoAssistencia> findByData(LocalDate data);
    List<SessaoAssistencia> findAllByOrderByDataDesc();

    // Sessões canceladas (a casa não abriu): bloqueiam presença na data e esticam a tolerância de
    // ausência da regra dos 21 dias (ver TratamentoService).
    boolean existsByDataAndCanceladaEmIsNotNull(LocalDate data);

    List<SessaoAssistencia> findByDataBetweenAndCanceladaEmIsNotNull(LocalDate inicio, LocalDate fim);

    // Janela de check-in aberta: a abertura só é permitida na própria data e fecha a janela
    // esquecida de outra data, então na prática isto devolve no máximo uma linha.
    Optional<SessaoAssistencia> findFirstByCheckinAbertoEmIsNotNullAndCheckinFechadoEmIsNull();

    /** Sessões de dias anteriores que foram abertas e ninguém encerrou (encerramento automático). */
    List<SessaoAssistencia> findByDataBeforeAndCheckinAbertoEmIsNotNullAndEncerradaEmIsNullAndCanceladaEmIsNull(LocalDate hoje);
}
