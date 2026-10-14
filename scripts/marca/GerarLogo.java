import java.awt.*;
import java.awt.font.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Gera o logo da R25TA Tecnologia (proposta D, "Degraus", escolhida em 2026-10-13) com as letras da fonte
 * Sora convertidas em paths, para o SVG não depender de fonte instalada.
 *
 * Uso: java -Djava.awt.headless=true scripts/marca/GerarLogo.java <pasta com Sora-600.ttf e Sora-700.ttf> docs/marca
 * Os TTF vêm do Google Fonts (licença OFL): o CSS de https://fonts.googleapis.com/css2?family=Sora:wght@600;700
 * pedido com um User-Agent antigo (curl -A "Mozilla/4.0") aponta para os arquivos .ttf.
 * Depois de gerar, copie docs/marca/r25ta-logo.svg para src/main/resources/static/images/.
 */
public class GerarLogo {
    static final String AZUL = "#2850D8", AMBAR = "#F2A23A";

    public static void main(String[] a) throws Exception {
        Path fontes = Path.of(a[0]), saida = Path.of(a[1]);
        Files.createDirectories(saida);
        Font bold = Font.createFont(Font.TRUETYPE_FONT, fontes.resolve("Sora-700.ttf").toFile());
        Font semi = Font.createFont(Font.TRUETYPE_FONT, fontes.resolve("Sora-600.ttf").toFile());

        Shape nome = texto(bold.deriveFont(30f), "R25TA", 74, 38, 0);
        Shape sub = texto(semi.deriveFont(11.5f), "TECNOLOGIA", 75, 56, 4.2);
        double largura = Math.ceil(Math.max(nome.getBounds2D().getMaxX(), sub.getBounds2D().getMaxX()) + 2);

        Shape[] barras = {
            poligono(4, 52, 14, 52, 24, 34, 14, 34),
            poligono(18, 52, 28, 52, 42, 24, 32, 24),
            poligono(32, 52, 42, 52, 60, 12, 50, 12)
        };
        double[] opacidade = {0.45, 0.75, 1};
        Shape ponto = new Ellipse2D.Double(58 - 4.5, 52 - 4.5, 9, 9);

        // Logo: SVG claro, escuro e com currentColor (para embutir no HTML)
        for (String[] v : new String[][]{{"r25ta-logo.svg", "#172033"}, {"r25ta-logo-escuro.svg", "#EEF2FA"},
                                         {"r25ta-logo-currentcolor.svg", "currentColor"}}) {
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %.0f 64\" role=\"img\" aria-label=\"R25TA Tecnologia\">", largura));
            for (int i = 0; i < 3; i++)
                s.append(String.format(Locale.ROOT, "<path d=\"%s\" fill=\"%s\" fill-opacity=\"%s\"/>", d(barras[i]), AZUL, opacidade[i]));
            s.append(String.format("<path d=\"%s\" fill=\"%s\"/>", d(ponto), AMBAR));
            s.append(String.format("<path d=\"%s\" fill=\"%s\"/>", d(nome), v[1]));
            s.append(String.format("<path d=\"%s\" fill=\"%s\" fill-opacity=\"0.7\"/>", d(sub), v[1]));
            s.append("</svg>\n");
            Files.writeString(saida.resolve(v[0]), s.toString());
        }

        // Ícone 64x64
        Shape fundo = new RoundRectangle2D.Double(0, 0, 64, 64, 28, 28);
        Shape[] barrasIcone = {
            poligono(10, 50, 18, 50, 26, 36, 18, 36),
            poligono(21, 50, 29, 50, 40, 28, 32, 28),
            poligono(32, 50, 40, 50, 54, 18, 46, 18)
        };
        double[] opIcone = {0.55, 0.8, 1};
        Shape pontoIcone = new Ellipse2D.Double(51 - 4, 50 - 4, 8, 8);
        StringBuilder ic = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" role=\"img\" aria-label=\"R25TA Tecnologia\">");
        ic.append("<rect width=\"64\" height=\"64\" rx=\"14\" fill=\"#172033\"/>");
        for (int i = 0; i < 3; i++)
            ic.append(String.format(Locale.ROOT, "<path d=\"%s\" fill=\"#6F8CFF\" fill-opacity=\"%s\"/>", d(barrasIcone[i]), opIcone[i]));
        ic.append(String.format("<path d=\"%s\" fill=\"%s\"/>", d(pontoIcone), AMBAR)).append("</svg>\n");
        Files.writeString(saida.resolve("r25ta-icone.svg"), ic.toString());

        // PNGs (fundo transparente)
        for (String[] v : new String[][]{{"r25ta-logo.png", "#172033"}, {"r25ta-logo-escuro.png", "#EEF2FA"}}) {
            double escala = 1200 / largura;
            BufferedImage img = new BufferedImage(1200, (int) Math.round(64 * escala), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = desenho(img, escala);
            for (int i = 0; i < 3; i++) pinta(g, barras[i], AZUL, opacidade[i]);
            pinta(g, ponto, AMBAR, 1);
            pinta(g, nome, v[1], 1);
            pinta(g, sub, v[1], 0.7);
            g.dispose();
            ImageIO.write(img, "png", saida.resolve(v[0]).toFile());
        }
        BufferedImage icone = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = desenho(icone, 8);
        pinta(g, fundo, "#172033", 1);
        for (int i = 0; i < 3; i++) pinta(g, barrasIcone[i], "#6F8CFF", opIcone[i]);
        pinta(g, pontoIcone, AMBAR, 1);
        g.dispose();
        ImageIO.write(icone, "png", saida.resolve("r25ta-icone-512.png").toFile());
        System.out.println("largura do logo: " + largura);
    }

    static Shape texto(Font f, String s, double x, double y, double espaco) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        GlyphVector gv = f.createGlyphVector(frc, s);
        for (int i = 0; i < gv.getNumGlyphs(); i++) {
            Point2D p = gv.getGlyphPosition(i);
            gv.setGlyphPosition(i, new Point2D.Double(p.getX() + i * espaco, p.getY()));
        }
        return gv.getOutline((float) x, (float) y);
    }

    static Shape poligono(double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        return p;
    }

    static String d(Shape s) {
        StringBuilder b = new StringBuilder();
        double[] c = new double[6];
        for (PathIterator it = s.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> b.append('M').append(n(c[0])).append(' ').append(n(c[1]));
                case PathIterator.SEG_LINETO -> b.append('L').append(n(c[0])).append(' ').append(n(c[1]));
                case PathIterator.SEG_QUADTO -> b.append('Q').append(n(c[0])).append(' ').append(n(c[1])).append(' ')
                        .append(n(c[2])).append(' ').append(n(c[3]));
                case PathIterator.SEG_CUBICTO -> b.append('C').append(n(c[0])).append(' ').append(n(c[1])).append(' ')
                        .append(n(c[2])).append(' ').append(n(c[3])).append(' ').append(n(c[4])).append(' ').append(n(c[5]));
                case PathIterator.SEG_CLOSE -> b.append('Z');
            }
        }
        return b.toString();
    }

    static String n(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }

    static Graphics2D desenho(BufferedImage img, double escala) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(escala, escala);
        return g;
    }

    static void pinta(Graphics2D g, Shape s, String cor, double op) {
        Color c = Color.decode(cor);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(op * 255)));
        g.fill(s);
    }
}
