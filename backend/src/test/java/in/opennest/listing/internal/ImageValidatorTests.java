package in.opennest.listing.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import in.opennest.common.RequestFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertThat(validated.toString()).isEqualTo("Validated[private]");
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
