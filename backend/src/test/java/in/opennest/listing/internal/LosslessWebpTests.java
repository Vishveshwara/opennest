package in.opennest.listing.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import in.opennest.common.RequestFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LosslessWebpTests {
    private static final String RGB = "UklGRioAAABXRUJQVlA4TB4AAAAvAUAAABAIJEHsr7qGkCAh+/+vAoEkkv0t73/YcAU=";
    private static final String RGBA = "UklGRjAAAABXRUJQVlA4TCQAAAAvAUAAEBAIJEHsr7qGkCAh+/+vAoEkkv0tFwgQ0qQ5F/bEVAI=";

    @ParameterizedTest @MethodSource("fixtures")
    void retainsGenuineLibwebpLosslessPayloadByteForByte(String fixture) {
        byte[] original = Base64.getDecoder().decode(fixture);
        var result = LosslessWebp.validate(original);
        assertThat(result.type()).isEqualTo("image/webp");
        assertThat(result.width()).isEqualTo(2); assertThat(result.height()).isEqualTo(2);
        assertThat(result.bytes()).containsExactly(original);
    }

    static Stream<String> fixtures() { return Stream.of(RGB, RGBA); }

    @Test void preservesAllRgbaChannelsIncludingRgbUnderFullTransparency() throws Exception {
        var result = LosslessWebp.validate(Base64.getDecoder().decode(RGBA));
        var image = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        try {
            assertThat(image.getRGB(0, 0)).isEqualTo(0xffff0000);
            assertThat(image.getRGB(1, 0)).isEqualTo(0x8000ff00);
            assertThat(image.getRGB(0, 1)).isEqualTo(0x000000ff);
            assertThat(image.getRGB(1, 1)).isEqualTo(0x400a141e);
        } finally { image.flush(); }
    }

    @Test void stripsIccXmpAndUnknownMetadataWhileKeepingTheVp8lPayload() {
        byte[] original = Base64.getDecoder().decode(RGBA);
        byte[] extended = riff(chunk("VP8X", extendedHeader(2, 2, 0x34)), chunk("ICCP", "fabricated-private-profile".getBytes(StandardCharsets.US_ASCII)),
                Arrays.copyOfRange(original, 12, original.length), chunk("XMP ", "private location metadata".getBytes(StandardCharsets.US_ASCII)),
                chunk("TEST", new byte[]{1, 2, 3}), chunk("TEST", new byte[0]));
        assertThat(LosslessWebp.validate(extended).bytes()).containsExactly(original);
    }

    @ParameterizedTest @ValueSource(strings = {"VP8 ", "ALPH", "ANIM", "ANMF", "EXIF"})
    void rejectsLossyAlphaAnimationAndExifChunks(String kind) {
        byte[] original = Base64.getDecoder().decode(RGBA);
        invalid(riff(Arrays.copyOfRange(original, 12, original.length), chunk(kind, new byte[10])));
    }

    @Test void rejectsRiffSizeMismatchTruncationAndTrailingData() {
        byte[] original = Base64.getDecoder().decode(RGB);
        byte[] wrongSize = original.clone(); ByteBuffer.wrap(wrongSize).order(ByteOrder.LITTLE_ENDIAN).putInt(4, original.length);
        invalid(wrongSize);
        invalid(Arrays.copyOf(original, original.length - 1));
        invalid(Arrays.copyOf(original, original.length + 1));
        invalid(riff(Arrays.copyOfRange(original, 12, original.length), new byte[]{1, 2, 3}));
    }

    @Test void rejectsDuplicateImageMetadataAndMisplacedExtendedHeaders() {
        byte[] original = Base64.getDecoder().decode(RGB);
        byte[] imageChunk = Arrays.copyOfRange(original, 12, original.length);
        invalid(riff(imageChunk, imageChunk));
        invalid(riff(imageChunk, chunk("VP8X", extendedHeader(2, 2, 0))));
        invalid(riff(chunk("VP8X", extendedHeader(2, 2, 0)), chunk("VP8X", extendedHeader(2, 2, 0)), imageChunk));
        invalid(riff(imageChunk, chunk("XMP ", new byte[0]), chunk("XMP ", new byte[0])));
    }

    @Test void rejectsNonzeroPaddingAndOutOfBoundsChunkLengths() {
        byte[] original = Base64.getDecoder().decode(RGB);
        byte[] metadata = chunk("TEST", new byte[]{1}); metadata[metadata.length - 1] = 1;
        invalid(riff(Arrays.copyOfRange(original, 12, original.length), metadata));
        byte[] badLength = original.clone(); ByteBuffer.wrap(badLength).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -1);
        invalid(badLength);
    }

    @Test void rejectsNonzeroVersionsBadSignaturesAndInvalidPixelStreams() {
        byte[] original = Base64.getDecoder().decode(RGB);
        byte[] version = original.clone(); version[24] |= 0x20; invalid(version);
        byte[] signature = original.clone(); signature[20] = 0; invalid(signature);
        invalid(riff(chunk("VP8L", Arrays.copyOfRange(original, 20, 25))));
        invalid(riff(chunk("VP8L", new byte[]{0x2f, 1, 0, 0, 0, 0, 0, 0})));
        invalid(riff(chunk("TEST", new byte[0])));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 8, 64, 128})
    void rejectsReservedAnimationAndExifFeatureFlags(int flag) {
        byte[] original = Base64.getDecoder().decode(RGB);
        if (flag == 8) {
            invalid(riff(chunk("VP8X", extendedHeader(2, 2, flag)), Arrays.copyOfRange(original, 12, original.length), chunk("EXIF", new byte[0])));
        } else invalid(riff(chunk("VP8X", extendedHeader(2, 2, flag)), Arrays.copyOfRange(original, 12, original.length)));
    }

    @Test void rejectsCanvasMismatchOversizeAndReservedHeaderBytes() {
        byte[] original = Base64.getDecoder().decode(RGB);
        byte[] imageChunk = Arrays.copyOfRange(original, 12, original.length);
        invalid(riff(chunk("VP8X", extendedHeader(3, 2, 0)), imageChunk));
        invalid(riff(chunk("VP8X", extendedHeader(16385, 2, 0)), imageChunk));
        byte[] reserved = extendedHeader(2, 2, 0); reserved[2] = 1;
        invalid(riff(chunk("VP8X", reserved), imageChunk));
    }

    @Test void resourceAdmissionUsesHeapBudgetRatherThanAFixedMegapixelLimit() {
        assertThatCode(() -> LosslessWebp.requireCapacity(6000, 4000, 1000, 1_000_000_000L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> LosslessWebp.requireCapacity(6000, 4000, 1000, 100_000_000L))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.UNAVAILABLE));
    }

    private static byte[] extendedHeader(int width, int height, int flags) {
        byte[] header = new byte[10]; header[0] = (byte) flags;
        for (int offset = 0; offset < 3; offset++) {
            header[4 + offset] = (byte) ((width - 1) >>> (8 * offset));
            header[7 + offset] = (byte) ((height - 1) >>> (8 * offset));
        }
        return header;
    }

    private static byte[] chunk(String type, byte[] bytes) {
        return ByteBuffer.allocate(8 + bytes.length + bytes.length % 2).order(ByteOrder.LITTLE_ENDIAN)
                .put(type.getBytes(StandardCharsets.US_ASCII)).putInt(bytes.length).put(bytes).array();
    }

    private static byte[] riff(byte[]... chunks) {
        var payload = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) payload.writeBytes(chunk);
        return ByteBuffer.allocate(12 + payload.size()).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".getBytes(StandardCharsets.US_ASCII))
                .putInt(4 + payload.size()).put("WEBP".getBytes(StandardCharsets.US_ASCII)).put(payload.toByteArray()).array();
    }

    private static void invalid(byte[] bytes) {
        assertThatThrownBy(() -> LosslessWebp.validate(bytes)).isInstanceOfSatisfying(RequestFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.INVALID_INPUT));
    }
}
