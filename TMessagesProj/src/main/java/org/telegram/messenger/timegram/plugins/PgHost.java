package org.telegram.messenger.timegram.plugins;

import java.util.List;

/**
 * Everything a .pg plugin can ever reach in the client. This list IS the plugin API: a Lua script can
 * call only what is implemented here and exposed in the {@code tg} table.
 * <p>
 * There is intentionally nothing for: the login session / account security, Telegram Stars, NFT gifts,
 * payments, Premium, files or the network. A guard test fails if such a method is ever added.
 * <p>
 * Implementations must return quickly and must NOT block on other threads (post work to the right
 * thread instead). Methods are called from the plugin worker thread.
 */
public interface PgHost {

    final class Message {
        public final long id;
        public final long dialogId;
        public final long fromId;
        public final long date; // seconds
        public final PluginEvent.ChatKind kind;
        public final boolean out;
        public final String text; // may be null

        public Message(long id, long dialogId, long fromId, long date, PluginEvent.ChatKind kind, boolean out, String text) {
            this.id = id;
            this.dialogId = dialogId;
            this.fromId = fromId;
            this.date = date;
            this.kind = kind;
            this.out = out;
            this.text = text;
        }
    }

    final class Chat {
        public final long id;
        public final String title;
        public final PluginEvent.ChatKind kind;
        public final int unread;

        public Chat(long id, String title, PluginEvent.ChatKind kind, int unread) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.unread = unread;
        }
    }

    void log(String pluginId, String text);

    void showToast(String text);

    void vibrate(int milliseconds);

    void muteChat(long dialogId, int minutes);

    void sendMessage(long dialogId, String text);

    void deleteMessage(long dialogId, long messageId);

    List<Chat> getChats(int limit);

    List<Message> getMessages(long dialogId, int limit);
}
