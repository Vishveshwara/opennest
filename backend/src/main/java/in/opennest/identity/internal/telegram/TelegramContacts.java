package in.opennest.identity.internal.telegram;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import in.opennest.common.RequestFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "opennest.identity.telegram-enabled", havingValue = "true")
class TelegramContacts {
    private static final String ISSUER = "https://telegram.org";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TelegramChallenges challenges;
    private final TelegramCrypto crypto;
    private final TelegramSettings settings;
    private final TelegramUpdates updates;
    TelegramContacts(JdbcTemplate jdbc, PlatformTransactionManager manager, TelegramChallenges challenges, TelegramCrypto crypto, TelegramSettings settings, TelegramUpdates updates) {
        this.jdbc = jdbc; transaction = new TransactionTemplate(manager); this.challenges = challenges; this.crypto = crypto; this.settings = settings; this.updates = updates;
    }
    String session(String id) { return crypto.hmac("login-session", id); }
    UUID initiator(String session) { return UUID.nameUUIDFromBytes(session.getBytes(StandardCharsets.UTF_8)); }
    Map<String, Object> create(String session, String phone) {
        if (phone == null || !phone.matches("\\+91[0-9]{10}")) throw invalid();
        String token = crypto.token();
        UUID id = challenges.create(initiator(session), session, phone, token);
        var challenge = challenges.find(id);
        return Map.of("challengeId", id, "botUrl", "https://t.me/" + settings.username() + "?start=" + token,
                "expiresAt", Instant.ofEpochSecond(challenge.expires()), "restartAt", Instant.ofEpochSecond(challenge.created() + 60), "status", "WAITING_BOT");
    }
    Map<String, Object> status(UUID id, String session) {
        challenges.poll(initiator(session));
        if (receipt(id, session) != null) return Map.of("status", "COMPLETED");
        var challenge = owned(id, session);
        return view(challenge);
    }
    Map<String, Object> resend(UUID id, String session) {
        var challenge = owned(id, session);
        if (!"CODE_SENT".equals(challenge.state()) && !"CODE_READY".equals(challenge.state())) throw conflict();
        updates.resend(challenge);
        return view(owned(id, session));
    }
    private Map<String, Object> view(TelegramChallenges.Challenge challenge) {
        return Map.of("status", challenge.state(), "expiresAt", Instant.ofEpochSecond(challenge.expires()),
                "resendAt", Instant.ofEpochSecond(challenge.resendAt()), "resendsRemaining", Math.max(0, 3 - challenge.resends()));
    }
    Optional<UUID> complete(UUID id, String session, String code, String fullName) {
        if (code == null || !code.matches("[0-9]{6}")) throw invalid();
        Optional<UUID> user = transact(() -> {
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, id.toString());
            UUID completed = receipt(id, session);
            if (completed != null) return Optional.of(completed);
            var challenge = owned(id, session);
            if (!challenges.claim(id, initiator(session), session, code)) throw conflict();
            challenge = owned(id, session);
            String sender = challenge.chat();
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,1))", Object.class, sender);
            var identities = jdbc.queryForList("select user_id from auth_identities where issuer=? and subject=?", ISSUER, sender);
            UUID account;
            if (!identities.isEmpty()) account = (UUID) identities.getFirst().get("user_id");
            else {
                var prior = jdbc.queryForList("select user_id from contact_verifications where telegram_user_id=?", Long.parseLong(sender));
                if (prior.size() > 1) throw conflict();
                if (prior.isEmpty() && (fullName == null || fullName.isBlank())) return Optional.empty();
                account = prior.isEmpty() ? UUID.randomUUID() : (UUID) prior.getFirst().get("user_id");
                if (prior.isEmpty()) jdbc.update("insert into users(id,full_name) values (?,?)", account, name(fullName));
                jdbc.update("insert into auth_identities(id,user_id,issuer,subject) values (?,?,?,?)", UUID.randomUUID(), account, ISSUER, sender);
            }
            var accounts = jdbc.queryForList("select phone_number from users where id=? for update", account);
            if (accounts.isEmpty()) throw new RequestFailure(RequestFailure.Kind.UNAUTHENTICATED);
            var existingPhone = accounts.getFirst().get("phone_number");
            if (existingPhone != null && !challenge.phone().equals(existingPhone)) throw conflict();
            jdbc.update("update users set phone_number=?,phone_verified_at=statement_timestamp() where id=?", challenge.phone(), account);
            jdbc.update("""
                    insert into contact_verifications(id,user_id,phone_number,telegram_user_id,session_digest)
                    values (?,?,?,?,?) on conflict (user_id) do update set id=excluded.id,phone_number=excluded.phone_number,
                    telegram_user_id=excluded.telegram_user_id,session_digest=excluded.session_digest,verified_at=statement_timestamp()
                    """, id, account, challenge.phone(), Long.parseLong(sender), session);
            jdbc.update("insert into telegram_login_receipts(id,user_id,session_digest,expires_at) values (?,?,?,?)",
                    id, account, session, Timestamp.from(Instant.ofEpochSecond(challenge.expires())));
            return Optional.of(account);
        });
        if (user.isPresent()) try { challenges.verified(id); } catch (org.springframework.dao.DataAccessException unavailable) { }
        return user;
    }
    private String name(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ").strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 120
                || normalized.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return normalized;
    }
    private TelegramChallenges.Challenge owned(UUID id, String session) {
        var challenge = challenges.find(id);
        if (challenge == null) throw conflict();
        if (!challenge.user().equals(initiator(session)) || !challenge.session().equals(session)) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
        return challenge;
    }
    private UUID receipt(UUID id, String session) {
        var rows = jdbc.queryForList("select user_id,session_digest from telegram_login_receipts where id=? and expires_at>statement_timestamp()", id);
        if (rows.isEmpty()) return null;
        if (!session.equals(rows.getFirst().get("session_digest"))) throw new RequestFailure(RequestFailure.Kind.NOT_FOUND);
        return (UUID) rows.getFirst().get("user_id");
    }
    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    void cleanup() { jdbc.update("delete from telegram_login_receipts where expires_at<=statement_timestamp()"); }
    private <T> T transact(Supplier<T> work) {
        var original = new RuntimeException[1];
        try {
            return transaction.execute(status -> {
                try { return work.get(); } catch (RuntimeException failure) { original[0] = failure; throw failure; }
            });
        } catch (RuntimeException failure) {
            if (original[0] != null && original[0] != failure) { original[0].addSuppressed(failure); throw original[0]; }
            throw failure;
        }
    }
    private static RequestFailure invalid() { return new RequestFailure(RequestFailure.Kind.INVALID_INPUT); }
    private static RequestFailure conflict() { return new RequestFailure(RequestFailure.Kind.CONFLICT); }
}
