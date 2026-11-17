package in.opennest.listing.internal;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import in.opennest.common.RequestFailure;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

final class AvifImage {
    static final int MAX_BYTES = 4_000_000;
    static final int MAX_EDGE = 2048;

    static ImageValidator.Validated validate(byte[] bytes) {
        ListingInput.require(bytes.length > 0 && bytes.length <= MAX_BYTES);
        var top = boxes(bytes, 0, bytes.length, List.of("ftyp", "meta", "mdat"));
        exact(bytes, top[0], "6176696600000000617669666d6966316d6961664d413142");
        var meta = top[1];
        ListingInput.require(integer(bytes, meta.body()) == 0);
        var children = boxes(bytes, meta.body() + 4, meta.end(), List.of("hdlr", "pitm", "iloc", "iinf", "iprp"));
        exact(bytes, children[0], "0000000000000000706963740000000000000000000000006c69626176696600");
        exact(bytes, children[1], "000000000001");
        exact(bytes, children[3], "0000000000010000001a696e6665020000000001000061763031436f6c6f7200");
        var location = children[2];
        ListingInput.require(location.end() - location.body() == 22);
        ListingInput.require(Arrays.equals(Arrays.copyOfRange(bytes, location.body(), location.body() + 14),
                HexFormat.of().parseHex("0000000044000001000100000001")));
        ListingInput.require(integer(bytes, location.body() + 14) == top[2].body()
                && integer(bytes, location.body() + 18) == top[2].end() - top[2].body());
        var properties = boxes(bytes, children[4].body(), children[4].end(), List.of("ipco", "ipma"));
        exact(bytes, properties[1], "000000000000000100010401028304");
        var color = boxes(bytes, properties[0].body(), properties[0].end(), List.of("ispe", "pixi", "av1C", "colr"));
        ListingInput.require(color[0].end() - color[0].body() == 12 && integer(bytes, color[0].body()) == 0);
        int width = integer(bytes, color[0].body() + 4), height = integer(bytes, color[0].body() + 8);
        ListingInput.require(width > 0 && height > 0 && width <= MAX_EDGE && height <= MAX_EDGE);
        exact(bytes, color[1], "0000000003080808");
        ListingInput.require(color[2].end() - color[2].body() == 4 && bytes[color[2].body()] == (byte) 0x81
                && (bytes[color[2].body() + 2] & 0xfc) == 0x0c && bytes[color[2].body() + 3] == 0);
        exact(bytes, color[3], "6e636c7800020002000680");
        if (16L * width * height + 3L * bytes.length > Runtime.getRuntime().maxMemory() / 4L)
            throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
        decode(bytes, width, height);
        return new ImageValidator.Validated(bytes, "image/avif", width, height);
    }

    private static void decode(byte[] bytes, int width, int height) {
        try {
            FFmpegFrameGrabber.tryLoad();
        } catch (FrameGrabber.Exception | LinkageError failure) {
            throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
        }
        try {
            av_log_set_level(AV_LOG_QUIET);
            try (var decoder = new FFmpegFrameGrabber(new ByteArrayInputStream(bytes), bytes.length)) {
                decoder.setFormat("mov");
                decoder.setImageMode(FrameGrabber.ImageMode.RAW);
                decoder.setVideoOption("threads", "1");
                decoder.setVideoOption("max_pixels", Integer.toString(MAX_EDGE * MAX_EDGE));
                decoder.setVideoOption("err_detect", "explode");
                decoder.setOption("protocol_whitelist", "file,pipe");
                decoder.start(false);
                ListingInput.require(decoder.getVideoCodec() == AV_CODEC_ID_AV1 && decoder.getFormatContext().nb_streams() == 1);
                var frame = decoder.grabImage();
                ListingInput.require(frame != null && frame.imageWidth == width && frame.imageHeight == height && frame.imageDepth == 8 && frame.image != null);
                ListingInput.require(decoder.grabImage() == null);
            }
        } catch (FrameGrabber.Exception failure) {
            throw ListingInput.invalid();
        } catch (LinkageError failure) {
            throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
        }
    }

    private static Box[] boxes(byte[] bytes, int start, int end, List<String> types) {
        var result = new Box[types.size()];
        for (int index = 0; index < types.size(); index++) {
            ListingInput.require(start >= 0 && start + 8L <= end);
            long size = Integer.toUnsignedLong(integer(bytes, start));
            ListingInput.require(size >= 8 && start + size <= end);
            ListingInput.require(new String(bytes, start + 4, 4, StandardCharsets.US_ASCII).equals(types.get(index)));
            result[index] = new Box(start + 8, start + (int) size);
            start += (int) size;
        }
        ListingInput.require(start == end);
        return result;
    }

    private static void exact(byte[] bytes, Box box, String hex) {
        ListingInput.require(Arrays.equals(Arrays.copyOfRange(bytes, box.body(), box.end()), HexFormat.of().parseHex(hex)));
    }

    private static int integer(byte[] bytes, int offset) {
        ListingInput.require(offset >= 0 && offset + 4L <= bytes.length);
        return ByteBuffer.wrap(bytes, offset, 4).getInt();
    }

    private record Box(int body, int end) {}
}
