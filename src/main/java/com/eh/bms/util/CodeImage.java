package com.eh.bms.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.util.Map;

/** Barcode (Code 128) and QR code pictures for product labels (20.4), made with ZXing. */
public final class CodeImage {

    /** The two kinds of code a label can carry. */
    public enum Kind {
        BARCODE, QR
    }

    private static final int BARCODE_WIDTH = 480;
    private static final int BARCODE_HEIGHT = 120;
    private static final int QR_SIZE = 240;

    private CodeImage() {
    }

    /** True if {@code text} can be a Code 128 barcode: printable ASCII only (QR takes any text). */
    public static boolean fitsBarcode(String text) {
        return text != null && !text.isBlank() && text.chars().allMatch(c -> c >= 32 && c < 127);
    }

    /** The code of {@code text} as a black-on-white picture, or null when it cannot be made. */
    public static Image create(String text, Kind kind) {
        try {
            BitMatrix matrix = kind == Kind.QR
                    ? new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE,
                    Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 1,
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M))
                    : new Code128Writer().encode(text, BarcodeFormat.CODE_128, BARCODE_WIDTH, BARCODE_HEIGHT);
            return toImage(matrix);
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Image toImage(BitMatrix matrix) {
        WritableImage image = new WritableImage(matrix.getWidth(), matrix.getHeight());
        PixelWriter writer = image.getPixelWriter();
        int black = 0xFF000000;
        int white = 0xFFFFFFFF;
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                writer.setArgb(x, y, matrix.get(x, y) ? black : white);
            }
        }
        return image;
    }
}
