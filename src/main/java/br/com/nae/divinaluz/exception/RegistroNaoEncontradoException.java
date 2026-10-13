package br.com.nae.divinaluz.exception;

/**
 * O id da URL não corresponde a nenhum registro (link antigo, cadastro apagado, número digitado errado).
 * Vira 404 em {@code TratamentoDeErros}, e não 500 (2026-10-13).
 *
 * <p>Estende {@link IllegalArgumentException} porque era esta a exceção lançada antes: quem já a
 * tratava continua tratando.</p>
 */
public class RegistroNaoEncontradoException extends IllegalArgumentException {
    public RegistroNaoEncontradoException(String message) {
        super(message);
    }
}
