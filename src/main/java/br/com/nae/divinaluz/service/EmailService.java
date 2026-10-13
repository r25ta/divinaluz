package br.com.nae.divinaluz.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import br.com.nae.divinaluz.exception.EnvioDeEmailException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * E-mails do acesso ao sistema: o código de entrada (ver CodigoAcessoService) e o aviso de boas-vindas
 * (ver AcessoService). Sem {@code app.mail.habilitado=true} configurado (precisa de credenciais SMTP
 * reais — ver application.properties), o e-mail não é enviado de verdade: o conteúdo fica só no log,
 * para continuar testável em dev sem depender de um provedor de e-mail configurado.
 *
 * <p>Uma falha do servidor de e-mail vira {@link EnvioDeEmailException} (2026-10-13), com o motivo no
 * log: antes, ela subia como erro 500 — e um SMTP fora do ar derrubava até o cadastro, que já tinha
 * sido gravado. Os tempos de espera do SMTP ficam em application.properties, para um servidor que não
 * responde não prender a tela.</p>
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
        try {
            mailSender.send(mensagem);
        } catch (MailException e) {
            // Nem o destinatário nem o assunto vão para o log: o e-mail de quem frequenta a casa é dado
            // pessoal, e o assunto do código de entrada carrega o próprio código.
            log.error("Falha ao enviar e-mail: {}", e.getMessage());
            throw new EnvioDeEmailException("O servidor de e-mail não respondeu e a mensagem não foi enviada. "
                    + "Tente de novo em alguns minutos.", e);
        }
    }
}
