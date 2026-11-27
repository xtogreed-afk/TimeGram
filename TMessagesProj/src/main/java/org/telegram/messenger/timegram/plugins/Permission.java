package org.telegram.messenger.timegram.plugins;

/**
 * Everything a plugin may ask for. A plugin declares the permissions it needs, the user grants them
 * (all or some) when installing, and the engine refuses any action whose permission was not granted.
 * There is deliberately NO permission, and no API at all, for: the login session or account security
 * (login tokens, 2FA, devices, phone number, passcode, secret chat keys), Telegram Stars, NFT gifts,
 * payments and Premium, files or the network. What does not exist in the API cannot be called.
 */
public enum Permission {
    /** Show a short message on the screen. */
    TOAST("toast"),
    /** Short vibration. */
    VIBRATE("vibrate"),
    /** Mute a chat for a while. */
    MUTE_CHATS("mute_chats"),
    /** See the text of incoming messages (conditions on text, {text} in toasts, tg.get_messages). */
    READ_MESSAGES("read_messages"),
    /** See the list of chats (tg.get_chats). */
    READ_CHATS("read_chats"),
    /** Send a message as the user (tg.send_message), rate limited. */
    SEND_MESSAGES("send_messages"),
    /** Delete a message (tg.delete_message). */
    DELETE_MESSAGES("delete_messages");

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
