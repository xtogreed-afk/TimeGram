package org.telegram.messenger.timegram.plugins;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs plugin rules. Safety is built into the engine, not left to plugins:
 * <ul>
 * <li><b>No code.</b> Plugins are data ({@link Plugin}); the engine only matches simple conditions and calls
 * three harmless methods of {@link PluginHost}.</li>
 * <li><b>No recursion.</b> Only the client reports events. While the engine is running actions, any new event
 * (for example one caused by muting a chat) is dropped instead of handled. There is no way for a plugin to
 * emit an event or call another plugin, so chains and loops cannot be written at all.</li>
 * <li><b>Permissions.</b> An action runs only if the user granted its permission.</li>
 * <li><b>Budget.</b> At most {@link #MAX_ACTIONS_PER_MINUTE} actions per plugin per minute. A plugin that keeps
 * hitting the limit is switched off until the user turns it on again.</li>
 * <li><b>Isolation.</b> A host error in one action never stops other plugins.</li>
 * </ul>
 */
public final class PluginEngine {

    public static final int MAX_ACTIONS_PER_MINUTE = 30;
    public static final int MAX_VIOLATIONS = 3;
    /** a hard stop for one event, whatever the plugins contain */
    public static final int MAX_ACTIONS_PER_EVENT = 40;
    public static final int MAX_TOAST_TEXT_FROM_MESSAGE = 100;
    private static final long WINDOW_MS = 60_000;

    public interface Clock {
        long now();
    }

    public interface Listener {
        /** A plugin was switched off by the engine. */
        void onPluginDisabled(String pluginId, String reason);
    }

    private static final class Entry {
        final Plugin plugin;
        boolean enabled;
        Set<Permission> granted;
        final ArrayDeque<Long> actionTimes = new ArrayDeque<>();
        int violations;
        String disabledReason;

        Entry(Plugin plugin, Set<Permission> granted, boolean enabled) {
            this.plugin = plugin;
            this.granted = granted;
            this.enabled = enabled;
        }
    }

    private final PluginHost host;
    private final Clock clock;
    private final Map<String, Entry> plugins = new LinkedHashMap<>();
    private Listener listener;

    /** true while actions are being executed: a second dispatch is a recursion and is refused */
    private boolean dispatching;
    private int droppedReentrant;

    public PluginEngine(PluginHost host, Clock clock) {
        this.host = host;
        this.clock = clock;
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Adds or replaces a plugin. Only permissions the plugin declared can be granted. */
    public synchronized void install(Plugin plugin, Set<Permission> granted, boolean enabled) {
        Set<Permission> effective = EnumSet.noneOf(Permission.class);
        for (Permission p : granted) {
            if (plugin.permissions.contains(p)) {
                effective.add(p);
            }
        }
        plugins.put(plugin.id, new Entry(plugin, effective, enabled));
    }

    public synchronized void remove(String pluginId) {
        plugins.remove(pluginId);
    }

    /** The user turns a plugin on or off. Turning it on clears the strikes of the safety guard. */
    public synchronized void setEnabled(String pluginId, boolean enabled) {
        Entry e = plugins.get(pluginId);
        if (e == null) {
            return;
        }
        e.enabled = enabled;
        if (enabled) {
            e.violations = 0;
            e.disabledReason = null;
            e.actionTimes.clear();
        }
    }

    public synchronized boolean isEnabled(String pluginId) {
        Entry e = plugins.get(pluginId);
        return e != null && e.enabled;
    }

    /** Why the engine switched the plugin off, or null. */
    public synchronized String getDisabledReason(String pluginId) {
        Entry e = plugins.get(pluginId);
        return e == null ? null : e.disabledReason;
    }

    public synchronized int getDroppedReentrantEvents() {
        return droppedReentrant;
    }

    public synchronized List<Plugin> getPlugins() {
        List<Plugin> list = new ArrayList<>();
        for (Entry e : plugins.values()) {
            list.add(e.plugin);
        }
        return list;
    }

    /**
     * Reports an event. Returns how many actions were executed.
     * Called by the client only; if it is called while actions are running (recursion), the event is dropped.
     */
    public synchronized int dispatch(PluginEvent event) {
        if (dispatching) {
            droppedReentrant++;
            return 0;
        }
        dispatching = true;
        int executed = 0;
        try {
            for (Entry entry : new ArrayList<>(plugins.values())) {
                if (!entry.enabled) {
                    continue;
                }
                for (Plugin.Rule rule : entry.plugin.rules) {
                    if (rule.on != event.type || !matches(rule, entry, event)) {
                        continue;
                    }
                    for (Plugin.Action action : rule.actions) {
                        if (executed >= MAX_ACTIONS_PER_EVENT || !entry.enabled) {
                            break;
                        }
                        if (!entry.granted.contains(action.type.permission)) {
                            continue;
                        }
                        if (!takeBudget(entry)) {
                            break;
                        }
                        execute(action, entry, event);
                        executed++;
                    }
                }
            }
        } finally {
            dispatching = false;
        }
        return executed;
    }

    private boolean matches(Plugin.Rule rule, Entry entry, PluginEvent event) {
        for (Plugin.Condition c : rule.when) {
            switch (c.type) {
                case TEXT_CONTAINS:
                case TEXT_STARTS_WITH:
                case TEXT_EQUALS: {
                    // text is only visible to plugins that were granted read_messages
                    if (event.text == null || !entry.granted.contains(Permission.READ_MESSAGES)) {
                        return false;
                    }
                    String text = c.ignoreCase ? event.text.toLowerCase(Locale.ROOT) : event.text;
                    String value = c.ignoreCase ? c.text.toLowerCase(Locale.ROOT) : c.text;
                    boolean ok = c.type == Plugin.ConditionType.TEXT_CONTAINS ? text.contains(value)
                            : c.type == Plugin.ConditionType.TEXT_STARTS_WITH ? text.startsWith(value)
                            : text.equals(value);
                    if (!ok) {
                        return false;
                    }
                    break;
                }
                case CHAT_KIND:
                    if (event.chatKind != c.chatKind) {
                        return false;
                    }
                    break;
                case FROM_USER:
                    if (event.fromUserId != c.userId) {
                        return false;
                    }
                    break;
            }
        }
        return true;
    }

    /** Rolling one-minute budget. Returns false (and counts a strike) when the plugin is over it. */
    private boolean takeBudget(Entry entry) {
        long now = clock.now();
        while (!entry.actionTimes.isEmpty() && now - entry.actionTimes.peekFirst() >= WINDOW_MS) {
            entry.actionTimes.pollFirst();
        }
        if (entry.actionTimes.size() >= MAX_ACTIONS_PER_MINUTE) {
            entry.violations++;
            if (entry.violations >= MAX_VIOLATIONS) {
                entry.enabled = false;
                entry.disabledReason = "Too many actions per minute";
                if (listener != null) {
                    try {
                        listener.onPluginDisabled(entry.plugin.id, entry.disabledReason);
                    } catch (Throwable ignore) {
                    }
                }
            }
            return false;
        }
        entry.actionTimes.addLast(now);
        return true;
    }

    private void execute(Plugin.Action action, Entry entry, PluginEvent event) {
        try {
            switch (action.type) {
                case TOAST: {
                    String text = action.text;
                    if (text.contains("{text}")) {
                        text = text.replace("{text}", messageSnippet(event.text));
                    }
                    host.showToast(text);
                    break;
                }
                case VIBRATE:
                    host.vibrate(action.amount);
                    break;
                case MUTE_CHAT:
                    host.muteChat(event.dialogId, action.amount);
                    break;
            }
        } catch (Throwable ignore) {
            // a broken action must not break other plugins or the client
        }
    }

    private static String messageSnippet(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace('\n', ' ').replace('\r', ' ');
        return s.length() > MAX_TOAST_TEXT_FROM_MESSAGE ? s.substring(0, MAX_TOAST_TEXT_FROM_MESSAGE) + "…" : s;
    }
}
