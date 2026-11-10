package in.opennest.identity.internal.telegram;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import in.opennest.common.RequestFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "opennest.identity.telegram-enabled", havingValue = "true")
class TelegramChallenges {
    record Challenge(UUID id, UUID user, String session, String phone, String state, long created, long expires, String salt, String chat) {
        @Override public String toString() { return "Challenge[private]"; }
    }
    final StringRedisTemplate redis;
    final String prefix;
    private final TelegramCrypto crypto;
    TelegramChallenges(StringRedisTemplate redis, TelegramSettings settings, TelegramCrypto crypto) {
        this.redis = redis; this.prefix = "opennest:" + settings.realm() + ":telegram:"; this.crypto = crypto;
    }
    UUID create(UUID user, String session, String phone, String token) {
        var id = UUID.randomUUID(); long now = Instant.now().getEpochSecond();
        String result = script("""
                if redis.call('EXISTS',KEYS[4]) == 1 then return 'RATE' end
                redis.call('ZREMRANGEBYSCORE',KEYS[5],'-inf',ARGV[5]-3600)
                if redis.call('ZCARD',KEYS[5]) >= 5 then return 'RATE' end
                local old=redis.call('GET',KEYS[2])
                if old and redis.call('EXISTS',ARGV[8]..'challenge:'..old)==1 then redis.call('HSET',ARGV[8]..'challenge:'..old,'state','CANCELLED') end
                redis.call('HSET',KEYS[1],'id',ARGV[1],'user',ARGV[2],'session',ARGV[3],'phone',ARGV[4],
                    'created',ARGV[5],'expires',ARGV[5]+300,'salt',ARGV[6],'state','WAITING_BOT')
                redis.call('EXPIRE',KEYS[1],300)
                redis.call('SET',KEYS[2],ARGV[1],'EX',300)
                redis.call('SET',KEYS[3],ARGV[1],'EX',300)
                redis.call('SET',KEYS[4],'1','EX',60)
                redis.call('ZADD',KEYS[5],ARGV[5],ARGV[1]); redis.call('EXPIRE',KEYS[5],3600)
                return 'OK'
                """, List.of(key(id), prefix + "account:" + user, prefix + "link:" + crypto.digest(token), prefix + "cooldown:" + user,
                prefix + "rate:user:" + user),
                id.toString(), user.toString(), session, phone, Long.toString(now), crypto.token(), "", prefix);
        if (!"OK".equals(result)) throw new RequestFailure(RequestFailure.Kind.RATE_LIMITED);
        return id;
    }
    Challenge find(UUID id) {
        Map<String, String> values = redis.<String, String>opsForHash().entries(key(id));
        if (values.isEmpty()) return null;
        return new Challenge(id, UUID.fromString(values.get("user")), values.get("session"), values.get("phone"), values.get("state"),
                Long.parseLong(values.get("created")), Long.parseLong(values.get("expires")), values.get("salt"), values.get("chat"));
    }
    Challenge byToken(String token) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) return null;
        String id = redis.opsForValue().get(prefix + "link:" + crypto.digest(token));
        return id == null ? null : find(UUID.fromString(id));
    }
    Challenge byChat(long chat) {
        String id = redis.opsForValue().get(prefix + "chat:" + chat);
        return id == null ? null : find(UUID.fromString(id));
    }
    boolean bind(Challenge challenge, long chat) {
        return "OK".equals(script("""
                local state=redis.call('HGET',KEYS[1],'state')
                if not state or redis.call('GET',KEYS[2])~=ARGV[1] then return 'NO' end
                local bound=redis.call('HGET',KEYS[1],'chat')
                if state=='WAITING_CONTACT' and bound==ARGV[2] then return 'OK' end
                if state~='WAITING_BOT' then return 'NO' end
                local other=redis.call('GET',KEYS[3])
                if other and other~=ARGV[1] then
                    local otherState=redis.call('HGET',ARGV[3]..'challenge:'..other,'state')
                    if otherState and otherState~='CANCELLED' and otherState~='VERIFIED' then return 'NO' end
                end
                redis.call('HSET',KEYS[1],'chat',ARGV[2],'state','WAITING_CONTACT')
                redis.call('SET',KEYS[3],ARGV[1],'EX',redis.call('TTL',KEYS[1]))
                return 'OK'
                """, List.of(key(challenge.id()), prefix + "account:" + challenge.user(), prefix + "chat:" + chat),
                challenge.id().toString(), Long.toString(chat), prefix));
    }
    boolean prepare(Challenge challenge, long chat, String code) {
        return "OK".equals(script("""
                local state=redis.call('HGET',KEYS[1],'state')
                if redis.call('GET',KEYS[2])~=ARGV[1] or redis.call('HGET',KEYS[1],'chat')~=ARGV[2] then return 'NO' end
                if state~='WAITING_CONTACT' and state~='CODE_READY' then return 'NO' end
                redis.call('ZREMRANGEBYSCORE',KEYS[3],'-inf',ARGV[4]-3600)
                if not redis.call('ZSCORE',KEYS[3],ARGV[1]) and redis.call('ZCARD',KEYS[3]) >= 5 then return 'NO' end
                redis.call('ZADD',KEYS[3],ARGV[4],ARGV[1])
                redis.call('EXPIRE',KEYS[3],3600)
                redis.call('HSET',KEYS[1],'state','CODE_READY','code',ARGV[3])
                return 'OK'
                """, List.of(key(challenge.id()), prefix + "account:" + challenge.user(), prefix + "rate:phone:" + crypto.hmac("phone", challenge.phone())), challenge.id().toString(), Long.toString(chat), crypto.hmac("otp", challenge.id() + ":" + code), Long.toString(Instant.now().getEpochSecond())));
    }
    void sent(UUID id) { script("if redis.call('HGET',KEYS[1],'state')=='CODE_READY' then redis.call('HSET',KEYS[1],'state','CODE_SENT') end return 'OK'", List.of(key(id))); }
    boolean claim(UUID id, UUID user, String session, String code) {
        String result = script("""
                if redis.call('GET',KEYS[2])~=ARGV[1] or redis.call('HGET',KEYS[1],'user')~=ARGV[2] or redis.call('HGET',KEYS[1],'session')~=ARGV[3] then return 'NO' end
                local state=redis.call('HGET',KEYS[1],'state')
                if state~='CODE_SENT' and state~='COMPLETING' then return 'NO' end
                local expected=redis.call('HGET',KEYS[1],'code') or ''
                local same=string.len(expected)==string.len(ARGV[4])
                local different=0
                for i=1,64 do if string.byte(expected,i)~=string.byte(ARGV[4],i) then different=different+1 end end
                if not same or different~=0 then
                    if redis.call('HINCRBY',KEYS[1],'attempts',1)>=5 then redis.call('HSET',KEYS[1],'state','LOCKED') end
                    return 'NO'
                end
                redis.call('HSET',KEYS[1],'state','COMPLETING')
                return 'OK'
                """, List.of(key(id), prefix + "account:" + user), id.toString(), user.toString(), session, crypto.hmac("otp", id + ":" + code));
        return "OK".equals(result);
    }
    void verified(UUID id) { script("if redis.call('EXISTS',KEYS[1])==1 then redis.call('HSET',KEYS[1],'state','VERIFIED') end return 'OK'", List.of(key(id))); }
    void poll(UUID user) { if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(prefix + "status:" + user, "1", Duration.ofSeconds(2)))) throw new RequestFailure(RequestFailure.Kind.RATE_LIMITED); }
    void sendBudget(long chat) {
        String result = script("""
                if redis.call('EXISTS',KEYS[1])==1 then return 'NO' end
                if tonumber(redis.call('GET',KEYS[2]) or '0')>=25 then return 'NO' end
                redis.call('SET',KEYS[1],'1','PX',1000)
                redis.call('INCR',KEYS[2]); redis.call('EXPIRE',KEYS[2],2)
                return 'OK'
                """, List.of(prefix + "send:chat:" + chat, prefix + "send:second:" + Instant.now().getEpochSecond()));
        if (!"OK".equals(result)) throw new RequestFailure(RequestFailure.Kind.UNAVAILABLE);
    }
    String key(UUID id) { return prefix + "challenge:" + id; }
    String script(String source, List<String> keys, String... args) { return redis.execute(new DefaultRedisScript<>(source, String.class), keys, (Object[]) args); }
}
