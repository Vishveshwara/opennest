package in.opennest.identity.internal.telegram;

import tools.jackson.databind.JsonNode;

interface TelegramBot {
    enum Hint { OPEN_BROWSER, WAIT_FOR_RESEND, FINISH_BROWSER }
    void contact(long chat);
    void hint(long chat, Hint hint);
    void code(long chat, String code);
    void checkPolling();
    JsonNode updates(long offset);
}
