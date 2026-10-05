package br.com.nae.divinaluz.service;

import br.com.nae.divinaluz.DivinaluzApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Encerra as sessões que ninguém encerrou (2026-10-05): a sessão pode ficar aberta até 23:59:59 do
 * próprio dia (ver CheckinService.encerrarSessoesVencidas).
 *
 * <p>Roda em dois momentos, porque o plano gratuito do Render <strong>hiberna</strong> a cada 15
 * minutos sem uso, e uma aplicação dormindo não executa o agendamento: à meia-noite, se estiver
 * acordada, e sempre que ela sobe — o primeiro acesso depois da hibernação já encontra o dia
 * anterior encerrado.</p>
 *
 * <p>{@code @Lazy(false)} porque o perfil {@code prod} usa {@code lazy-initialization}: um bean
 * preguiçoso nunca seria criado, e o {@code @Scheduled} dele nunca seria registrado.</p>
 */
@Component
@Lazy(false)
public class EncerramentoAutomaticoSessoes implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncerramentoAutomaticoSessoes.class);

    private final CheckinService checkinService;

    public EncerramentoAutomaticoSessoes(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = DivinaluzApplication.FUSO)
    public void aMeiaNoite() {
        encerrar();
    }

    @Override
    public void run(ApplicationArguments args) {
        encerrar();
    }

    private void encerrar() {
        int encerradas = checkinService.encerrarSessoesVencidas(LocalDate.now());
        if (encerradas > 0) {
            log.info("Encerramento automático: {} sessão(ões) de dias anteriores encerrada(s) às 23:59:59 do dia.",
                    encerradas);
        }
    }
}
