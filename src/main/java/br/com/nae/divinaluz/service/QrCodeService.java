package br.com.nae.divinaluz.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/** Gera o PNG do QR code do cartão. Server-side para o QR também funcionar impresso. */
@Service
public class QrCodeService {

    public byte[] png(String conteudo, int tamanhoEmPixels) {
        try {
            // Correção de erro média: o cartão pode estar amassado/impresso, e a margem menor
            // aproveita melhor o espaço no cartão virtual.
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 1,
                    EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix matriz = new QRCodeWriter()
                    .encode(conteudo, BarcodeFormat.QR_CODE, tamanhoEmPixels, tamanhoEmPixels, hints);

            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matriz, "PNG", saida);
            return saida.toByteArray();
        } catch (com.google.zxing.WriterException | IOException e) {
            throw new IllegalStateException("Não foi possível gerar o QR code do cartão.", e);
        }
    }
}
