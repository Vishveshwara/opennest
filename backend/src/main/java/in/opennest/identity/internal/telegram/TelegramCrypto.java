package in.opennest.identity.internal.telegram;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
final class TelegramCrypto {
    private final TelegramSettings settings;
    private final SecureRandom random = new SecureRandom();
    TelegramCrypto(TelegramSettings settings) { this.settings = settings; }
    String token() { var bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("OpenNest cryptography unavailable"); }
    }
    String hmac(String purpose, String value) { return HexFormat.of().formatHex(mac(purpose + ":" + settings.realm() + ":" + value)); }
    String code(String salt) {
        long value = ByteBuffer.wrap(mac("code:" + settings.realm() + ":" + salt)).getLong();
        return String.format(java.util.Locale.ROOT, "%06d", Long.remainderUnsigned(value, 1_000_000));
    }
    private byte[] mac(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.hmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("OpenNest cryptography unavailable"); }
    }
}
