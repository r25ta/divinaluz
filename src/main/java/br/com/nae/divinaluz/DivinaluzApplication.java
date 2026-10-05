package br.com.nae.divinaluz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class DivinaluzApplication {

	/**
	 * Fuso da casa. Todo "hoje" e "agora" do sistema (LocalDate.now(), LocalDateTime.now()) é o horário
	 * de Brasília. Fixado aqui em 2026-10-05: o contêiner do Render roda em UTC, e para o sistema o dia
	 * virava às 21h — no meio da sessão de terça (19h), que passava a ser "de outro dia".
	 */
	public static final String FUSO = "America/Sao_Paulo";

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone(FUSO));
		SpringApplication.run(DivinaluzApplication.class, args);
	}

}
