package in.opennest.listing.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import in.opennest.common.RequestFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageValidatorTests {
    @Test void decodesStaticWebpAndWritesFreshPixels() {
        byte[] webp = java.util.Base64.getDecoder().decode("UklGRlYAAABXRUJQVlA4IDoAAADwAgCdASoBAAEAAEcIhYWIhYSIAgICdaoD+AP6Ag1NGAD+/vNYf/5gZt2KO//mBv/80F4SW6//zLwASUNNVAgAAAB0ZXN0MXgxAA==");
        var validated = ImageValidator.validate(webp, "image/webp");
        assertThat(validated.width()).isEqualTo(1);
        assertThat(validated.height()).isEqualTo(1);
        assertThat(validated.type()).isEqualTo("image/jpeg");
        assertThat(new String(validated.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("test1x1");
    }
    @ParameterizedTest @ValueSource(strings = {"jpeg", "png"})
    void decodesAndReencodesSupportedImagesWithoutChangingDimensions(String format) throws Exception {
        byte[] original = image(format, format.equals("png"));
        var validated = ImageValidator.validate(original, "image/" + format);
        assertThat(validated.width()).isEqualTo(24);
        assertThat(validated.height()).isEqualTo(16);
        assertThat(validated.type()).isEqualTo("image/" + format);
        var decoded = ImageIO.read(new ByteArrayInputStream(validated.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(24);
        assertThat(decoded.getHeight()).isEqualTo(16);
        assertColor(decoded.getRGB(12, 8), 0xff285078, format.equals("jpeg") ? 6 : 0);
        assertThat(validated.toString()).isEqualTo("Validated[private]");
    }

    @ParameterizedTest @CsvSource({"1,0,1,2,3", "2,1,0,3,2", "3,3,2,1,0", "4,2,3,0,1",
            "5,0,2,1,3", "6,2,0,3,1", "7,3,1,2,0", "8,1,3,0,2"})
    void appliesAllExifOrientationsToPixelsAndStripsTheTag(int orientation, int topLeft, int topRight, int bottomLeft, int bottomRight) throws Exception {
        int[] colors = {0xffd02020, 0xff20b040, 0xff2040c0, 0xffd0b020};
        var source = new BufferedImage(80, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 80; x++) source.setRGB(x, y, colors[(y >= 24 ? 2 : 0) + (x >= 40 ? 1 : 0)]);
        var encoded = new ByteArrayOutputStream();
        assertThat(ImageIO.write(source, "jpeg", encoded)).isTrue();
        for (var order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) for (boolean precedingEntry : new boolean[]{false, true}) {
            byte[] original = withOrientation(encoded.toByteArray(), orientation, order, precedingEntry);
            var validated = ImageValidator.validate(original, "image/jpeg");
            int width = orientation >= 5 ? 48 : 80, height = orientation >= 5 ? 80 : 48;
            assertThat(validated.width()).isEqualTo(width);
            assertThat(validated.height()).isEqualTo(height);
            assertThat(new String(validated.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
            var decoded = ImageIO.read(new ByteArrayInputStream(validated.bytes()));
            assertThat(decoded.getWidth()).isEqualTo(width);
            assertThat(decoded.getHeight()).isEqualTo(height);
            assertColor(decoded.getRGB(width / 4, height / 4), colors[topLeft], 12);
            assertColor(decoded.getRGB(width * 3 / 4, height / 4), colors[topRight], 12);
            assertColor(decoded.getRGB(width / 4, height * 3 / 4), colors[bottomLeft], 12);
            assertColor(decoded.getRGB(width * 3 / 4, height * 3 / 4), colors[bottomRight], 12);
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 9})
    void rejectsInvalidExifOrientationValues(int orientation) throws Exception {
        byte[] original = withOrientation(image("jpeg", false), orientation, ByteOrder.BIG_ENDIAN);
        invalid(() -> ImageValidator.validate(original, "image/jpeg"));
    }

    @Test void rejectsExifDirectoryOutsideItsMetadataSegment() throws Exception {
        byte[] original = withOrientation(image("jpeg", false), 6, ByteOrder.BIG_ENDIAN);
        ByteBuffer.wrap(original).putInt(16, Integer.MAX_VALUE);
        invalid(() -> ImageValidator.validate(original, "image/jpeg"));
    }

    @Test void removesPngTextMetadataFromFinalPixels() throws Exception {
        String secret = "private coordinates 18.123456,73.123456";
        byte[] original = image("png", true);
        byte[] text = chunk("tEXt", ("Comment\0" + secret).getBytes(StandardCharsets.ISO_8859_1));
        byte[] withMetadata = insert(original, 33, text);
        assertThat(new String(withMetadata, StandardCharsets.ISO_8859_1)).contains(secret);
        var validated = ImageValidator.validate(withMetadata, "image/png");
        assertThat(new String(validated.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain(secret, "Comment");
        assertThat(ImageIO.read(new ByteArrayInputStream(validated.bytes()))).isNotNull();
    }

    @Test void rejectsMimeMismatchAndUnsupportedSignatures() throws Exception {
        invalid(() -> ImageValidator.validate(imageUnchecked("jpeg", false), "image/png"));
        invalid(() -> ImageValidator.validate("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.US_ASCII), "image/png"));
        invalid(() -> ImageValidator.validate(new byte[]{71, 73, 70, 56, 57, 97}, "image/gif"));
        invalid(() -> ImageValidator.validate(new byte[0], "image/jpeg"));
        invalid(() -> ImageValidator.validate(new byte[ListingMedia.MAX_BYTES + 1], "image/jpeg"));
    }

    @Test void rejectsTruncatedJpegPngAndWebp() throws Exception {
        invalid(() -> ImageValidator.validate(new byte[]{(byte)255, (byte)216, (byte)255}, "image/jpeg"));
        invalid(() -> ImageValidator.validate(Arrays.copyOf(imageUnchecked("png", true), 20), "image/png"));
        invalid(() -> ImageValidator.validate("RIFF\4\0\0\0WEBP".getBytes(StandardCharsets.US_ASCII), "image/webp"));
    }

    @Test void rejectsPixelBombBeforeDecodingRaster() throws Exception {
        byte[] png = image("png", true);
        ByteBuffer.wrap(png).putInt(16, 20_000_001).putInt(20, 1);
        var crc = new CRC32(); crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        invalid(() -> ImageValidator.validate(png, "image/png"));
    }

    @Test void rejectsAnimatedPngControlChunk() throws Exception {
        byte[] control = ByteBuffer.allocate(8).putInt(2).putInt(0).array();
        byte[] animated = insert(image("png", true), 33, chunk("acTL", control));
        invalid(() -> ImageValidator.validate(animated, "image/png"));
    }

    @Test void rejectsAnimatedWebpChunksWithoutInvokingDecoder() {
        byte[] animation = new byte[]{'R','I','F','F',12,0,0,0,'W','E','B','P','A','N','I','M',0,0,0,0};
        invalid(() -> ImageValidator.validate(animation, "image/webp"));
    }

    @Test void rejectsChunkLengthsOutsideUploadedBytes() throws Exception {
        byte[] png = image("png", true);
        ByteBuffer.wrap(png).putInt(8, Integer.MAX_VALUE);
        invalid(() -> ImageValidator.validate(png, "image/png"));
    }

    private static byte[] image(String format, boolean alpha) throws Exception {
        var image = new BufferedImage(24, 16, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, 0xff285078);
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }
    private static byte[] withOrientation(byte[] jpeg, int orientation, ByteOrder order) {
        return withOrientation(jpeg, orientation, order, false);
    }
    private static byte[] withOrientation(byte[] jpeg, int orientation, ByteOrder order, boolean precedingEntry) {
        int metadataSize = precedingEntry ? 44 : 32;
        var metadata = ByteBuffer.allocate(metadataSize).order(order);
        metadata.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        metadata.put((byte) (order == ByteOrder.LITTLE_ENDIAN ? 'I' : 'M'));
        metadata.put((byte) (order == ByteOrder.LITTLE_ENDIAN ? 'I' : 'M'));
        metadata.putShort((short) 42).putInt(8).putShort((short) (precedingEntry ? 2 : 1));
        if (precedingEntry) metadata.putShort((short) 256).putShort((short) 4).putInt(1).putInt(80);
        metadata.putShort((short) 274).putShort((short) 3).putInt(1)
                .putShort((short) orientation).putShort((short) 0).putInt(0);
        var segment = ByteBuffer.allocate(metadataSize + 4).put((byte) 255).put((byte) 225).putShort((short) (metadataSize + 2)).put(metadata.array()).array();
        return insert(jpeg, 2, segment);
    }
    private static void assertColor(int actual, int expected, int tolerance) {
        assertThat(actual >>> 24).isEqualTo(255);
        for (int shift : new int[]{16, 8, 0}) {
            int expectedChannel = expected >> shift & 255;
            assertThat(actual >> shift & 255).isBetween(expectedChannel - tolerance, expectedChannel + tolerance);
        }
    }
    private static byte[] imageUnchecked(String format, boolean alpha) {
        try { return image(format, alpha); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static byte[] chunk(String type, byte[] content) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32(); crc.update(name); crc.update(content);
        return ByteBuffer.allocate(content.length + 12).putInt(content.length).put(name).put(content).putInt((int) crc.getValue()).array();
    }
    private static byte[] insert(byte[] source, int offset, byte[] addition) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(Arrays.copyOfRange(source, 0, offset)); output.writeBytes(addition); output.writeBytes(Arrays.copyOfRange(source, offset, source.length));
        return output.toByteArray();
    }
    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RequestFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.INVALID_INPUT));
    }
}
