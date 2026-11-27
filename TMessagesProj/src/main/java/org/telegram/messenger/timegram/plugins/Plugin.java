package org.telegram.messenger.timegram.plugins;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A parsed and validated plugin. It is plain data: there is no code in it, only rules
 * "when EVENT happens and CONDITIONS match, do ACTIONS". Build it with {@link PluginParser}.
 */
public final class Plugin {

    public enum ConditionType {
        TEXT_CONTAINS("text_contains", true),
        TEXT_STARTS_WITH("text_starts_with", true),
        TEXT_EQUALS("text_equals", true),
        CHAT_KIND("chat_kind", false),
        FROM_USER("from_user", false);

        public final String id;
        /** needs the READ_MESSAGES permission */
        public final boolean readsText;

        ConditionType(String id, boolean readsText) {
            this.id = id;
            this.readsText = readsText;
        }

        static ConditionType byId(String id) {
            for (ConditionType t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return null;
        }
    }

    public enum ActionType {
        TOAST("toast", Permission.TOAST),
        VIBRATE("vibrate", Permission.VIBRATE),
        MUTE_CHAT("mute_chat", Permission.MUTE_CHATS);

        public final String id;
        public final Permission permission;

        ActionType(String id, Permission permission) {
            this.id = id;
            this.permission = permission;
        }

        static ActionType byId(String id) {
            for (ActionType t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return null;
        }
    }

    public static final class Condition {
        public final ConditionType type;
        /** for text conditions */
        public final String text;
        public final boolean ignoreCase;
        /** for chat_kind */
        public final PluginEvent.ChatKind chatKind;
        /** for from_user */
        public final long userId;

        Condition(ConditionType type, String text, boolean ignoreCase, PluginEvent.ChatKind chatKind, long userId) {
            this.type = type;
            this.text = text;
            this.ignoreCase = ignoreCase;
            this.chatKind = chatKind;
            this.userId = userId;
        }
    }

    public static final class Action {
        public final ActionType type;
        /** toast text, may contain {text} */
        public final String text;
        /** vibrate milliseconds / mute minutes */
        public final int amount;

        Action(ActionType type, String text, int amount) {
            this.type = type;
            this.text = text;
            this.amount = amount;
        }
    }

    public static final class Rule {
        public final PluginEvent.Type on;
        public final List<Condition> when;
        public final List<Action> actions;

        Rule(PluginEvent.Type on, List<Condition> when, List<Action> actions) {
            this.on = on;
            this.when = Collections.unmodifiableList(when);
            this.actions = Collections.unmodifiableList(actions);
        }
    }

    public final String id;
    public final String name;
    public final String version;
    public final String description;
    public final Set<Permission> permissions;
    public final List<Rule> rules;

    Plugin(String id, String name, String version, String description, Set<Permission> permissions, List<Rule> rules) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.permissions = Collections.unmodifiableSet(EnumSet.copyOf(permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : permissions));
        this.rules = Collections.unmodifiableList(rules);
    }
}
