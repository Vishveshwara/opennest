package in.opennest.identity.internal.telegram;

import tools.jackson.databind.JsonNode;

interface TelegramBot {
    void contact(long chat);
    void code(long chat, String code);
    void checkPolling();
    JsonNode updates(long offset);
}
