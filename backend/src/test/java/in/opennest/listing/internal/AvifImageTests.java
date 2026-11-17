package in.opennest.listing.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import in.opennest.common.RequestFailure;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

class AvifImageTests {
    @Test
    void decodesAndPreservesValidAvifAboveFourMegabytes() throws Exception {
        byte[] bytes = AvifFixture.withPadding();
        assertThat(bytes.length).isGreaterThan(4_000_000);
        var result = ImageValidator.validate(bytes, "image/avif");
        assertThat(result.width()).isEqualTo(64);
        assertThat(result.height()).isEqualTo(48);
        assertThat(result.bytes()).isEqualTo(bytes);
    }

    @Test
    void reportsAnUnavailableNativeDecoderAsRetryableInfrastructureFailure() throws Exception {
        try (var decoder = mockStatic(FFmpegFrameGrabber.class)) {
            decoder.when(FFmpegFrameGrabber::tryLoad).thenThrow(new FFmpegFrameGrabber.Exception("Fabricated native load failure"));
            assertThatThrownBy(() -> ImageValidator.validate(fixture(), "image/avif"))
                    .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.UNAVAILABLE));
        }
    }

    @Test
    void decodesThePinnedBrowserEncoderWithoutReencoding() throws Exception {
        FFmpegFrameGrabber.tryLoad();
        try (var stream = getClass().getResourceAsStream("/images/browser-avif.avif")) {
            byte[] original = stream.readAllBytes();
            var result = ImageValidator.validate(original, "image/avif");
            assertThat(result.type()).isEqualTo("image/avif");
            assertThat(result.width()).isEqualTo(64);
            assertThat(result.height()).isEqualTo(48);
            assertThat(result.bytes()).containsExactly(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Exif", "mime", "grid", "jpeg"})
    void rejectsMetadataOtherCodecsAndHiddenImageItems(String type) throws Exception {
        var bytes = fixture();
        int offset = text(bytes, "av01");
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, bytes, offset, 4);
        invalid(bytes);
    }

    @Test void rejectsEmptyTruncatedTrailingAndAnimatedContainers() throws Exception {
        byte[] bytes = fixture();
        invalid(Arrays.copyOf(bytes, bytes.length - 1));
        invalid(Arrays.copyOf(bytes, bytes.length + 1));
        invalid(new byte[0]);
        bytes[11] = 's'; invalid(bytes);
    }

    @Test void rejectsInvalidBoxSizesAndExternalExtents() throws Exception {
        var bytes = fixture(); ByteBuffer.wrap(bytes).putInt(32, -1); invalid(bytes);
        bytes = fixture(); bytes[text(bytes, "iloc") + 19] = 1; invalid(bytes);
        bytes = fixture(); ByteBuffer.wrap(bytes).putInt(text(bytes, "iloc") + 18, Integer.MAX_VALUE); invalid(bytes);
    }

    @Test void rejectsOversizeAndDishonestDimensions() throws Exception {
        var bytes = fixture(); ByteBuffer.wrap(bytes).putInt(text(bytes, "ispe") + 8, 2049); invalid(bytes);
        bytes = fixture(); ByteBuffer.wrap(bytes).putInt(text(bytes, "ispe") + 8, 65); invalid(bytes);
    }

    @Test void rejectsCorruptPixelPayloadAndIncorrectContentType() throws Exception {
        var bytes = fixture(); Arrays.fill(bytes, text(bytes, "mdat") + 4, bytes.length, (byte) 0); invalid(bytes);
        assertThatThrownBy(() -> ImageValidator.validate(fixture(), "image/webp")).isInstanceOf(RequestFailure.class);
    }

    private byte[] fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/images/browser-avif.avif")) { return stream.readAllBytes(); }
    }

    private int text(byte[] bytes, String value) {
        return new String(bytes, StandardCharsets.ISO_8859_1).indexOf(value);
    }

    private void invalid(byte[] bytes) {
        assertThatThrownBy(() -> ImageValidator.validate(bytes, "image/avif"))
                .isInstanceOfSatisfying(RequestFailure.class, failure -> assertThat(failure.kind()).isEqualTo(RequestFailure.Kind.INVALID_INPUT));
    }
}
