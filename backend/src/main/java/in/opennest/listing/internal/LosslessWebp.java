package in.opennest.listing.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import in.opennest.common.RequestFailure;

final class LosslessWebp {
    /**
     * Validates one lossless pixel stream and strips container metadata without re-encoding the payload.
     */
    static ImageValidator.Validated validate(byte[] bytes) {
        ListingInput.require(bytes.length >= 26 && bytes.length <= ListingMedia.MAX_BYTES);
        ListingInput.require(ascii(bytes, 0).equals("RIFF") && ascii(bytes, 8).equals("WEBP") && unsigned(bytes, 4) + 8 == bytes.length);
        var seen = new HashSet<String>();
        int payloadOffset = -1, payloadSize = 0, width = 0, height = 0, canvasWidth = 0, canvasHeight = 0;
        for (int offset = 12; offset < bytes.length;) {
            ListingInput.require(offset + 8L <= bytes.length);
            String chunk = ascii(bytes, offset);
            if (chunk.equals("VP8X") || chunk.equals("VP8L") || chunk.equals("ICCP") || chunk.equals("XMP "))
                ListingInput.require(seen.add(chunk));
            long size = unsigned(bytes, offset + 4), next = offset + 8L + size + size % 2;
            ListingInput.require(next <= bytes.length && (size % 2 == 0 || bytes[(int) next - 1] == 0));
            int start = offset + 8;
            switch (chunk) {
                case "VP8X" -> {
                    ListingInput.require(offset == 12 && size == 10);
                    int flags = bytes[start] & 255;
                    ListingInput.require((flags & 0xc3) == 0 && bytes[start + 1] == 0 && bytes[start + 2] == 0 && bytes[start + 3] == 0);
                    canvasWidth = uint24(bytes, start + 4) + 1; canvasHeight = uint24(bytes, start + 7) + 1;
                    ListingInput.require(canvasWidth <= 16384 && canvasHeight <= 16384);
                }
                case "VP8L" -> {
                    ListingInput.require(size >= 5 && bytes[start] == 0x2f);
                    long header = unsigned(bytes, start + 1);
                    ListingInput.require((header >>> 29) == 0);
                    width = (int) (header & 0x3fff) + 1; height = (int) ((header >>> 14) & 0x3fff) + 1;
                    payloadOffset = start; payloadSize = (int) size;
                }
                case "VP8 ", "ALPH", "ANIM", "ANMF", "EXIF" -> throw ListingInput.invalid();
                default -> {}
            }
            offset = (int) next;
        }
        ListingInput.require(payloadOffset >= 0 && (canvasWidth == 0 || canvasWidth == width && canvasHeight == height));
        requireCapacity(width, height, bytes.length, Runtime.getRuntime().maxMemory());
        int outputSize = 20 + payloadSize + payloadSize % 2;
        var output = ByteBuffer.allocate(outputSize).order(ByteOrder.LITTLE_ENDIAN);
        output.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(outputSize - 8).put("WEBPVP8L".getBytes(StandardCharsets.US_ASCII))
                .putInt(payloadSize).put(bytes, payloadOffset, payloadSize);
        byte[] clean = output.array();
        decode(clean, width, height);
        return new ImageValidator.Validated(clean, "image/webp", width, height);
    }

    static void requireCapacity(int width, int height, int inputBytes, long maxMemory) {
        long required = 8L * width * height + 3L * inputBytes;
        if (required > maxMemory / 4) throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
    }

    private static void decode(byte[] bytes, int width, int height) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            ListingInput.require(readers.hasNext());
            var reader = readers.next();
            BufferedImage decoded = null;
            try {
                reader.setInput(input, false, true);
                ListingInput.require(reader.getWidth(0) == width && reader.getHeight(0) == height && reader.getNumImages(true) == 1);
                decoded = reader.read(0);
                ListingInput.require(decoded != null && decoded.getWidth() == width && decoded.getHeight() == height);
            } finally {
                if (decoded != null) decoded.flush();
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException failure) { throw ListingInput.invalid(); }
    }

    private static String ascii(byte[] bytes, int offset) { return new String(bytes, offset, 4, StandardCharsets.US_ASCII); }
    private static long unsigned(byte[] bytes, int offset) { return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()); }
    private static int uint24(byte[] bytes, int offset) { return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8 | (bytes[offset + 2] & 255) << 16; }
}
