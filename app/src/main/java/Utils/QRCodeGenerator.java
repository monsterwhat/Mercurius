package Utils;

import jakarta.annotation.Nonnull;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Generates QR code images for Hacienda V4.4 electronic invoices.
 * The QR code encodes the 50-digit Clave (tax document key) for
 * inclusion in the printed PDF receipt.
 */
public class QRCodeGenerator {

    private static final int QR_SIZE = 150;

    private QRCodeGenerator() {}

    /**
     * Generates a QR code PNG byte array from the given text (the Clave).
     *
     * @param text The 50-digit Clave to encode
     * @return PNG image bytes ready to embed in a PDF
     * @throws WriterException if the QR code cannot be generated
     * @throws IOException     if the PNG image cannot be written
     */
    @Nonnull
    public static byte[] generateQRCodeBytes(@Nonnull String text) throws WriterException, IOException {
        BufferedImage image = generateQRCodeImage(text);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Generates a QR code as a BufferedImage.
     *
     * @param text The text to encode (usually the 50-digit Clave)
     * @return QR code image
     * @throws WriterException if the QR code cannot be generated
     */
    @Nonnull
    public static BufferedImage generateQRCodeImage(@Nonnull String text) throws WriterException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }
}
