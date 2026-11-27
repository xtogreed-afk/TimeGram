package org.telegram.messenger.timegram.plugins;

/** A plugin file is invalid. The message is safe to show to the user. */
public class PluginException extends Exception {
    public PluginException(String message) {
        super(message);
    }
}
