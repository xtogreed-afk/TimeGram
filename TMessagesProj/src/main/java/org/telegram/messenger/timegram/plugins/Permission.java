package org.telegram.messenger.timegram.plugins;

/**
 * Everything a plugin may ask for. A plugin declares the permissions it needs, the user grants them
 * (all or some) when installing, and the engine refuses any action whose permission was not granted.
 * There is deliberately no permission for sending messages, reading contacts, files or the network.
 */
public enum Permission {
    /** Show a short message on the screen. */
    TOAST("toast"),
    /** Short vibration. */
    VIBRATE("vibrate"),
    /** Mute a chat for a while. */
    MUTE_CHATS("mute_chats"),
    /** See the text of incoming messages (conditions on text, {text} in toasts). */
    READ_MESSAGES("read_messages");

    public final String id;

    Permission(String id) {
        this.id = id;
    }

    public static Permission byId(String id) {
        for (Permission p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }
}
