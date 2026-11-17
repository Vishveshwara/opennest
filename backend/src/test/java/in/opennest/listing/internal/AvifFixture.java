package in.opennest.listing.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class AvifFixture {
    static byte[] withPadding() throws IOException {
        byte[] original;
        try (var stream = AvifFixture.class.getResourceAsStream("/images/browser-avif.avif")) {
            original = stream.readAllBytes();
        }
        int paddingBytes = 4_000_005;
        var bytes = Arrays.copyOf(original, original.length + paddingBytes);
        bytes[original.length] = 0x7a;
        int remaining = 4_000_000, offset = original.length + 1;
        do {
            int part = remaining & 0x7f;
            remaining >>>= 7;
            bytes[offset++] = (byte) (part | (remaining > 0 ? 0x80 : 0));
        } while (remaining > 0);
        bytes[bytes.length - 1] = (byte) 0x80;
        var names = new String(original, StandardCharsets.ISO_8859_1);
        var buffer = ByteBuffer.wrap(bytes);
        int mdat = names.indexOf("mdat") - 4, extent = names.indexOf("iloc") + 22;
        buffer.putInt(mdat, buffer.getInt(mdat) + paddingBytes);
        buffer.putInt(extent, buffer.getInt(extent) + paddingBytes);
        return bytes;
    }
}
