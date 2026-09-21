package br.com.nae.divinaluz.exception;

/** Violação de regra de negócio (ex.: sessão antes de 7 dias, avaliação pendente). */
public class RegraNegocioException extends RuntimeException {
    public RegraNegocioException(String message) {
        super(message);
    }
}
