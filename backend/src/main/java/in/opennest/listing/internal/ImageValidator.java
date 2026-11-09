package in.opennest.listing.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

final class ImageValidator {
    record Validated(byte[] bytes, String type, int width, int height) {
        @Override public String toString() { return "Validated[private]"; }
    }
    static Validated validate(byte[] bytes, String declaredType) {
        ListingInput.require(bytes.length > 0 && bytes.length <= ListingMedia.MAX_BYTES);
        String type = signature(bytes);
        ListingInput.require(type.equals(declaredType));
        rejectAnimation(bytes, type);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            ListingInput.require(readers.hasNext());
            var reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                ListingInput.require(width > 0 && height > 0 && (long) width * height <= 20_000_000 && reader.getNumImages(true) == 1);
                BufferedImage decoded = reader.read(0);
                var clean = new BufferedImage(width, height, decoded.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var graphics = clean.createGraphics();
                try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); }
                String format = clean.getColorModel().hasAlpha() ? "png" : "jpeg";
                var output = new ByteArrayOutputStream();
                try (var stream = new MemoryCacheImageOutputStream(output)) {
                    var writer = ImageIO.getImageWritersByFormatName(format).next();
                    try { writer.setOutput(stream); writer.write(clean); } finally { writer.dispose(); }
                }
                ListingInput.require(output.size() <= ListingMedia.MAX_BYTES);
                return new Validated(output.toByteArray(), "image/" + format, width, height);
            } finally { reader.dispose(); }
        } catch (java.io.IOException | IllegalArgumentException failure) { throw ListingInput.invalid(); }
    }
    private static String signature(byte[] data) {
        if (data.length >= 3 && (data[0] & 255) == 255 && (data[1] & 255) == 216 && (data[2] & 255) == 255) return "image/jpeg";
        if (data.length >= 8 && java.util.Arrays.equals(java.util.Arrays.copyOf(data, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if (data.length >= 12 && ascii(data, 0).equals("RIFF") && ascii(data, 8).equals("WEBP")) return "image/webp";
        throw ListingInput.invalid();
    }
    private static String ascii(byte[] data, int offset) { return new String(data, offset, 4, StandardCharsets.US_ASCII); }
    private static void rejectAnimation(byte[] bytes, String type) {
        int offset = type.equals("image/png") ? 8 : type.equals("image/webp") ? 12 : bytes.length;
        while (offset + 8 <= bytes.length) {
            boolean png = type.equals("image/png");
            String chunk = ascii(bytes, png ? offset + 4 : offset);
            ListingInput.require(!chunk.equals("acTL") && !chunk.equals("ANIM") && !chunk.equals("ANMF"));
            long length = 0;
            for (int i = 0; i < 4; i++) length |= (long) (bytes[offset + (png ? i : i + 4)] & 255) << (png ? (3 - i) * 8 : i * 8);
            long next = offset + 8L + length + (png ? 4 : length % 2);
            ListingInput.require(next <= bytes.length && next > offset);
            offset = (int) next;
        }
    }
}
