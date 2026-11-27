package org.telegram.messenger.timegram.plugins;

/**
 * The only things the engine can do to the client. This is the whole "API surface" of plugins:
 * three small, harmless operations. The client implements it; the engine never touches Android directly.
 */
public interface PluginHost {
    void showToast(String text);

    void vibrate(int milliseconds);

    void muteChat(long dialogId, int minutes);
}
