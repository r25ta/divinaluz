package br.com.nae.divinaluz.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrCodeServiceTest {

    private final QrCodeService qrCodeService = new QrCodeService();

    @Test
    void qrCodeGeradoVoltaAUrlDeCheckinQuandoLido() throws Exception {
        String urlCheckin = "http://localhost:8081/divinaluz/checkin/3e596e3f-14da-4644-b3b6-65b580078b1c";

        byte[] png = qrCodeService.png(urlCheckin, 320);

        assertTrue(png.length > 0, "o PNG não deveria vir vazio");
        var imagem = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(320, imagem.getWidth());

        // Lê de volta o próprio QR: garante que o que a recepção escaneia é a URL de check-in.
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(imagem)));
        assertEquals(urlCheckin, new QRCodeReader().decode(bitmap).getText());
    }
}
