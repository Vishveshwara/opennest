package in.opennest.identity.internal.telegram;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import in.opennest.common.RequestFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "opennest.identity.telegram-enabled", havingValue = "true")
class TelegramUpdates {
    private final TelegramChallenges challenges;
    private final TelegramCrypto crypto;
    private final TelegramBot bot;
    TelegramUpdates(TelegramChallenges challenges, TelegramCrypto crypto, TelegramBot bot) { this.challenges = challenges; this.crypto = crypto; this.bot = bot; }
    void accept(JsonNode update) {
        if (!update.isObject() || !update.path("update_id").isIntegralNumber() || !update.path("update_id").canConvertToLong() || update.path("update_id").asLong() < 0)
            throw new RequestFailure(RequestFailure.Kind.INVALID_INPUT);
        String key = challenges.prefix + "update:" + update.path("update_id").asLong();
        String lease = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(challenges.redis.opsForValue().setIfAbsent(key, lease, Duration.ofSeconds(30)))) {
            if ("DONE".equals(challenges.redis.opsForValue().get(key))) return;
            throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
        }
        try {
            message(update.path("message"));
            challenges.script("if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('SET',KEYS[1],'DONE','EX',86400) end return 'OK'", List.of(key), lease);
        } catch (RuntimeException failure) {
            try { challenges.script("if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('DEL',KEYS[1]) end return 'OK'", List.of(key), lease); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private void message(JsonNode message) {
        if (!message.isObject() || !"private".equals(message.path("chat").path("type").asString("") )
                || message.path("from").path("is_bot").asBoolean(true) || message.has("forward_origin") || message.has("forward_date")) return;
        var chatNode = message.path("chat").path("id"); var sender = message.path("from").path("id");
        if (!chatNode.isIntegralNumber() || !chatNode.canConvertToLong() || !sender.isIntegralNumber() || !sender.canConvertToLong()) return;
        long chat = chatNode.asLong();
        if (chat <= 0 || chat != sender.asLong()) return;
        if (!message.path("date").isIntegralNumber() || !message.path("date").canConvertToLong()) return;
        long date = message.path("date").asLong(), now = Instant.now().getEpochSecond();
        if (date < now - 300 || date > now + 30) return;
        String text = message.path("text").asString("");
        if (text.startsWith("/start ")) {
            var challenge = challenges.byToken(text.substring(7));
            if (challenge == null || date < challenge.created() - 1 || !challenges.bind(challenge, chat)) return;
            challenges.sendBudget(chat); bot.contact(chat); return;
        }
        var contact = message.path("contact");
        if (!contact.isObject() || !contact.path("user_id").isIntegralNumber() || !contact.path("user_id").canConvertToLong() || contact.path("user_id").asLong() != chat) return;
        String phone = contact.path("phone_number").asString("");
        if (phone.matches("91[0-9]{10}")) phone = "+" + phone;
        var challenge = challenges.byChat(chat);
        if (challenge == null || date < challenge.created() - 1 || !phone.equals(challenge.phone())) return;
        String code = crypto.code(challenge.salt());
        if (!challenges.prepare(challenge, chat, code)) return;
        challenges.sendBudget(chat); bot.code(chat, code); challenges.sent(challenge.id());
    }
}
