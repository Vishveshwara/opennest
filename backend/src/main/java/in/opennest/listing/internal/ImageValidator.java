package in.opennest.listing.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.geom.AffineTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        int orientation = type.equals("image/jpeg") ? jpegOrientation(bytes) : 1;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            ListingInput.require(readers.hasNext());
            var reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                ListingInput.require(width > 0 && height > 0 && (long) width * height <= 20_000_000 && reader.getNumImages(true) == 1);
                BufferedImage decoded = reader.read(0);
                int outputWidth = orientation >= 5 ? height : width, outputHeight = orientation >= 5 ? width : height;
                var clean = new BufferedImage(outputWidth, outputHeight, decoded.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var graphics = clean.createGraphics();
                try { graphics.drawImage(decoded, transform(orientation, width, height), null); } finally { graphics.dispose(); }
                String format = clean.getColorModel().hasAlpha() ? "png" : "jpeg";
                var output = new ByteArrayOutputStream();
                try (var stream = new MemoryCacheImageOutputStream(output)) {
                    var writer = ImageIO.getImageWritersByFormatName(format).next();
                    try { writer.setOutput(stream); writer.write(clean); } finally { writer.dispose(); }
                }
                ListingInput.require(output.size() <= ListingMedia.MAX_BYTES);
                return new Validated(output.toByteArray(), "image/" + format, outputWidth, outputHeight);
            } finally { reader.dispose(); }
        } catch (java.io.IOException | IllegalArgumentException failure) { throw ListingInput.invalid(); }
    }
    private static AffineTransform transform(int orientation, int width, int height) {
        return switch (orientation) {
            case 1 -> new AffineTransform();
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> throw ListingInput.invalid();
        };
    }
    private static int jpegOrientation(byte[] bytes) {
        int offset = 2;
        while (offset < bytes.length) {
            ListingInput.require((bytes[offset] & 255) == 255);
            while (offset < bytes.length && (bytes[offset] & 255) == 255) offset++;
            ListingInput.require(offset < bytes.length);
            int marker = bytes[offset++] & 255;
            if (marker == 218 || marker == 217) return 1;
            if (marker == 1 || marker >= 208 && marker <= 215) continue;
            ListingInput.require(marker != 0 && offset + 2 <= bytes.length);
            int length = (bytes[offset] & 255) << 8 | bytes[offset + 1] & 255;
            ListingInput.require(length >= 2 && offset + length <= bytes.length);
            if (marker == 225 && length >= 8 && bytes[offset + 2] == 'E' && bytes[offset + 3] == 'x'
                    && bytes[offset + 4] == 'i' && bytes[offset + 5] == 'f' && bytes[offset + 6] == 0 && bytes[offset + 7] == 0)
                return exifOrientation(ByteBuffer.wrap(bytes, offset + 8, length - 8).slice());
            offset += length;
        }
        return 1;
    }
    private static int exifOrientation(ByteBuffer tiff) {
        ListingInput.require(tiff.limit() >= 8);
        boolean little = tiff.get(0) == 'I' && tiff.get(1) == 'I';
        ListingInput.require(little || tiff.get(0) == 'M' && tiff.get(1) == 'M');
        tiff.order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        ListingInput.require(Short.toUnsignedInt(tiff.getShort(2)) == 42);
        long directory = Integer.toUnsignedLong(tiff.getInt(4));
        ListingInput.require(directory >= 8 && directory + 2 <= tiff.limit());
        int entries = Short.toUnsignedInt(tiff.getShort((int) directory));
        ListingInput.require(directory + 2 + 12L * entries <= tiff.limit());
        for (int entry = (int) directory + 2, index = 0; index < entries; index++, entry += 12) {
            if (Short.toUnsignedInt(tiff.getShort(entry)) != 274) continue;
            ListingInput.require(Short.toUnsignedInt(tiff.getShort(entry + 2)) == 3 && tiff.getInt(entry + 4) == 1);
            int orientation = Short.toUnsignedInt(tiff.getShort(entry + 8));
            ListingInput.require(orientation >= 1 && orientation <= 8);
            return orientation;
        }
        return 1;
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
