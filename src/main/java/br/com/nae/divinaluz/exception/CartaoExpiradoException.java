package br.com.nae.divinaluz.exception;

/**
 * O assistido passou do limite de ausência (21 dias): o cartão foi marcado como
 * "Incompleto por Tempo" e a presença só entra depois que a recepção confirmar o reinício em P2.
 */
public class CartaoExpiradoException extends RegraNegocioException {
    public CartaoExpiradoException(String message) {
        super(message);
    }
}
