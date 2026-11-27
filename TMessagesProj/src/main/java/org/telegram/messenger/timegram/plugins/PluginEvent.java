package org.telegram.messenger.timegram.plugins;

/**
 * An event that the client reports to the engine. Only the client creates events: a plugin can never
 * emit one, so plugins cannot trigger each other or themselves.
 */
public final class PluginEvent {

    public enum Type {
        MESSAGE_RECEIVED("message_received"),
        APP_OPENED("app_opened");

        public final String id;

        Type(String id) {
            this.id = id;
        }

        public static Type byId(String id) {
            for (Type t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return null;
        }
    }

    public enum ChatKind {
        PRIVATE("private"), GROUP("group"), CHANNEL("channel"), UNKNOWN("unknown");

        public final String id;

        ChatKind(String id) {
            this.id = id;
        }

        public static ChatKind byId(String id) {
            for (ChatKind k : values()) {
                if (k.id.equals(id)) {
                    return k;
                }
            }
            return null;
        }
    }

    public final Type type;
    public final long dialogId;
    public final ChatKind chatKind;
    public final long fromUserId;
    /** Message text, or null. */
    public final String text;

    private PluginEvent(Type type, long dialogId, ChatKind chatKind, long fromUserId, String text) {
        this.type = type;
        this.dialogId = dialogId;
        this.chatKind = chatKind;
        this.fromUserId = fromUserId;
        this.text = text;
    }

    public static PluginEvent messageReceived(long dialogId, ChatKind chatKind, long fromUserId, String text) {
        return new PluginEvent(Type.MESSAGE_RECEIVED, dialogId, chatKind, fromUserId, text);
    }

    public static PluginEvent appOpened() {
        return new PluginEvent(Type.APP_OPENED, 0, ChatKind.UNKNOWN, 0, null);
    }
}
