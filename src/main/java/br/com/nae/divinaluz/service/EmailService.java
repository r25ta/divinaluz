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

    /** Envio real ligado? Sem isso a entrada por código não funciona (o código só iria ao log). */
    public boolean isHabilitado() {
        return habilitado;
    }

    public void enviarDefinicaoSenha(String destinatario, String nomeAssistido, String link) {
        String assunto = "Defina sua senha de acesso — N.A.E. Divina Luz";
        String corpo = "Olá, " + nomeAssistido + "!\n\n"
                + "Seu cadastro foi criado no sistema do N.A.E. Divina Luz. Para acessar seu "
                + "cartão de assistência espiritual, defina sua senha pelo link abaixo (válido por "
                + "48 horas):\n\n" + link + "\n\n"
                + "Se você não reconhece esse cadastro, pode ignorar este e-mail.";

        enviar(destinatario, assunto, corpo, "Link de definição de senha: " + link);
    }

    /**
     * Código de entrada (ver CodigoAcessoService). O código vai no <strong>assunto</strong> também, de
     * propósito: no celular dá para ler na lista de mensagens, sem nem abrir o e-mail.
     */
    public void enviarCodigoAcesso(String destinatario, String nomeAssistido, String codigo, int validadeMinutos) {
        String assunto = codigo + " — seu código de acesso do N.A.E. Divina Luz";
        String corpo = "Olá, " + nomeAssistido + "!\n\n"
                + "Seu código para entrar no sistema do N.A.E. Divina Luz é:\n\n"
                + "    " + codigo + "\n\n"
                + "O código vale por " + validadeMinutos + " minutos e só pode ser usado uma vez.\n\n"
                + "Se não foi você que pediu, ignore este e-mail — ninguém entra na sua conta sem este "
                + "código.";

        enviar(destinatario, assunto, corpo, "Código de acesso: " + codigo);
    }

    /**
     * Avisa que a recepção criou o cadastro e que a entrada é pelo e-mail, sem senha. Não carrega
     * código nem link com prazo: assim não existe o "expirou e eu perdi" — a pessoa pede o código
     * quando for usar.
     */
    public void enviarBoasVindas(String destinatario, String nomeAssistido, String urlEntrada) {
        String assunto = "Seu acesso ao N.A.E. Divina Luz";
        String corpo = "Olá, " + nomeAssistido + "!\n\n"
                + "Seu cadastro foi criado no sistema do N.A.E. Divina Luz. Para ver seu cartão de "
                + "assistência espiritual, acesse:\n\n" + urlEntrada + "\n\n"
                + "Você não precisa de senha: basta informar este endereço de e-mail e nós enviamos um "
                + "código de 6 dígitos na hora.\n\n"
                + "Se você não reconhece esse cadastro, pode ignorar este e-mail.";

        enviar(destinatario, assunto, corpo, "Endereço de entrada: " + urlEntrada);
    }

    // Sem app.mail.habilitado=true, nada sai: o que importaria para o destinatário vai para o log, e é
    // só por isso que o fluxo continua testável em dev sem um provedor SMTP configurado.
    private void enviar(String destinatario, String assunto, String corpo, String resumoParaLog) {
        if (!habilitado) {
            log.info("[E-MAIL DESABILITADO — app.mail.habilitado=false] Para: {} | {}\n{}",
                    destinatario, assunto, resumoParaLog);
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
