package in.opennest.identity.internal.telegram;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "opennest.identity.telegram-enabled", havingValue = "true")
class TelegramPolling {
    private final TelegramSettings settings;
    private final TelegramChallenges challenges;
    private final TelegramUpdates updates;
    private final TelegramBot bot;
    private boolean ready;
    TelegramPolling(TelegramSettings settings, TelegramChallenges challenges, TelegramUpdates updates, TelegramBot bot) {
        this.settings = settings; this.challenges = challenges; this.updates = updates; this.bot = bot;
    }
    @Scheduled(initialDelay = 15000, fixedDelay = 1000)
    void poll() {
        if (!settings.polling()) return;
        String key = challenges.prefix + "poller", lease = UUID.randomUUID().toString();
        try {
            if (!Boolean.TRUE.equals(challenges.redis.opsForValue().setIfAbsent(key, lease, Duration.ofMinutes(2)))) return;
            try {
                if (!ready) { bot.checkPolling(); ready = true; }
                String current = challenges.redis.opsForValue().get(challenges.prefix + "offset");
                long offset = current == null ? 0 : Long.parseLong(current);
                var batch = bot.updates(offset);
                if (!batch.isArray() || batch.size() > 1) throw new IllegalStateException("Invalid OpenNest provider response");
                for (var update : batch) {
                    updates.accept(update);
                    long next = Math.addExact(update.path("update_id").asLong(), 1);
                    challenges.script("if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('SET',KEYS[2],ARGV[2]) end return 'OK'",
                            List.of(key, challenges.prefix + "offset"), lease, Long.toString(next));
                }
            } finally {
                challenges.script("if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('DEL',KEYS[1]) end return 'OK'", List.of(key), lease);
            }
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(TelegramPolling.class).error("OpenNest Telegram update processing unavailable");
        }
    }
}
