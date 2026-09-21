package br.com.nae.divinaluz.exception;

/** O assistido completou um bloco de 4 sessões e precisa de uma nova Avaliação antes de continuar. */
public class AvaliacaoPendenteException extends RegraNegocioException {
    public AvaliacaoPendenteException(String message) {
        super(message);
    }
}
