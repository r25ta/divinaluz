package br.com.nae.divinaluz.exception;

/**
 * O servidor de e-mail recusou ou não respondeu (2026-10-13). É uma {@link RegraNegocioException} para
 * a tela mostrar a mensagem em vez de um erro 500: um SMTP fora do ar não pode derrubar o cadastro nem
 * a recepção. Quem não depende do envio (aviso de boas-vindas, pedido de código pela própria pessoa)
 * só registra no log e segue.
 */
public class EnvioDeEmailException extends RegraNegocioException {
    public EnvioDeEmailException(String message, Throwable causa) {
        super(message);
        initCause(causa);
    }
}
