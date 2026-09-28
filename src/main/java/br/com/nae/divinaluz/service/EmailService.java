package br.com.nae.divinaluz.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Envio do e-mail de definição de senha do acesso do assistido (ver AcessoService). Sem
 * {@code app.mail.habilitado=true} configurado (precisa de credenciais SMTP reais — ver
 * application.properties), o e-mail não é enviado de verdade: o link fica só no log, para
 * continuar testável em dev sem depender de um provedor de e-mail configurado.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.habilitado:false}")
    private boolean habilitado;

    @Value("${app.mail.remetente:naeidivinaluz@example.com}")
    private String remetente;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void enviarDefinicaoSenha(String destinatario, String nomeAssistido, String link) {
        String assunto = "Defina sua senha de acesso — N.A.E. Divina Luz";
        String corpo = "Olá, " + nomeAssistido + "!\n\n"
                + "Seu cadastro foi criado no sistema do N.A.E. Divina Luz. Para acessar seu "
                + "cartão de assistência espiritual, defina sua senha pelo link abaixo (válido por "
                + "48 horas):\n\n" + link + "\n\n"
                + "Se você não reconhece esse cadastro, pode ignorar este e-mail.";

        if (!habilitado) {
            log.info("[E-MAIL DESABILITADO — app.mail.habilitado=false] Para: {} | {}\nLink de definição de senha: {}",
                    destinatario, assunto, link);
            return;
        }

        SimpleMailMessage mensagem = new SimpleMailMessage();
        mensagem.setFrom(remetente);
        mensagem.setTo(destinatario);
        mensagem.setSubject(assunto);
        mensagem.setText(corpo);
        mailSender.send(mensagem);
    }
}
