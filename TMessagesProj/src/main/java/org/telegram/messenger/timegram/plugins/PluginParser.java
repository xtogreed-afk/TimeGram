package org.telegram.messenger.timegram.plugins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the text of a plugin file into a {@link Plugin}, or throws {@link PluginException}.
 * <p>
 * The rules are strict on purpose: unknown keys are errors, every size is limited, an action needs a
 * declared permission, and nothing in the format can refer to another plugin, include a file, loop,
 * or run code. The same input always gives the same result.
 * <pre>
 * {
 *   "schema": 1,
 *   "id": "night.quiet", "name": "Night quiet", "version": "1.0",
 *   "description": "Mutes noisy chats at night",
 *   "permissions": ["read_messages", "mute_chats"],
 *   "rules": [
 *     { "on": "message_received",
 *       "when": [ { "type": "text_contains", "value": "sale" } ],
 *       "do":   [ { "type": "mute_chat", "minutes": 60 } ] }
 *   ]
 * }
 * </pre>
 */
public final class PluginParser {

    public static final int SCHEMA = 1;
    public static final int MAX_FILE_CHARS = 64 * 1024;
    public static final int MAX_RULES = 30;
    public static final int MAX_CONDITIONS = 5;
    public static final int MAX_ACTIONS = 4;
    public static final int MAX_TEXT = 200;
    public static final int MAX_VIBRATE_MS = 500;
    public static final int MAX_MUTE_MINUTES = 24 * 60;

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,47}");

    private PluginParser() {
    }

    public static Plugin parse(String text) throws PluginException {
        if (text == null || text.isEmpty()) {
            throw new PluginException("The file is empty");
        }
        if (text.length() > MAX_FILE_CHARS) {
            throw new PluginException("The file is too big (max " + (MAX_FILE_CHARS / 1024) + " KB)");
        }
        if (text.charAt(0) == '﻿') {
            text = text.substring(1); // BOM from some editors
        }
        Object root = PluginJson.parse(text);
        Map<String, Object> o = object(root, "plugin");
        keys(o, "plugin", set("schema", "id", "name", "version", "description", "permissions", "rules"), set("schema", "id", "name", "version", "rules"));

        if (integer(o.get("schema"), "schema") != SCHEMA) {
            throw new PluginException("Unsupported schema version (this TimeGram understands " + SCHEMA + ")");
        }
        String id = string(o.get("id"), "id", 1, 48);
        if (!ID.matcher(id).matches()) {
            throw new PluginException("\"id\" must be 3-48 characters: a-z, 0-9, '.', '_' or '-'");
        }
        String name = string(o.get("name"), "name", 1, 40);
        String version = string(o.get("version"), "version", 1, 16);
        String description = o.containsKey("description") ? string(o.get("description"), "description", 0, MAX_TEXT) : "";

        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        if (o.containsKey("permissions")) {
            for (Object p : list(o.get("permissions"), "permissions", 8)) {
                String pid = string(p, "permission", 1, 32);
                Permission permission = Permission.byId(pid);
                if (permission == null) {
                    throw new PluginException("Unknown permission \"" + pid + "\"");
                }
                if (!permissions.add(permission)) {
                    throw new PluginException("Permission \"" + pid + "\" is listed twice");
                }
            }
        }

        List<Object> rawRules = list(o.get("rules"), "rules", MAX_RULES);
        if (rawRules.isEmpty()) {
            throw new PluginException("A plugin needs at least one rule");
        }
        List<Plugin.Rule> rules = new ArrayList<>();
        for (int i = 0; i < rawRules.size(); i++) {
            rules.add(parseRule(rawRules.get(i), "rules[" + i + "]", permissions));
        }
        return new Plugin(id, name, version, description, permissions, rules);
    }

    private static Plugin.Rule parseRule(Object raw, String where, Set<Permission> declared) throws PluginException {
        Map<String, Object> o = object(raw, where);
        keys(o, where, set("on", "when", "do"), set("on", "do"));

        String onId = string(o.get("on"), where + ".on", 1, 32);
        PluginEvent.Type on = PluginEvent.Type.byId(onId);
        if (on == null) {
            throw new PluginException(where + ": unknown event \"" + onId + "\"");
        }

        List<Plugin.Condition> when = new ArrayList<>();
        if (o.containsKey("when")) {
            List<Object> rawWhen = list(o.get("when"), where + ".when", MAX_CONDITIONS);
            for (int i = 0; i < rawWhen.size(); i++) {
                when.add(parseCondition(rawWhen.get(i), where + ".when[" + i + "]", on, declared));
            }
        }

        List<Object> rawDo = list(o.get("do"), where + ".do", MAX_ACTIONS);
        if (rawDo.isEmpty()) {
            throw new PluginException(where + ": \"do\" needs at least one action");
        }
        List<Plugin.Action> actions = new ArrayList<>();
        for (int i = 0; i < rawDo.size(); i++) {
            actions.add(parseAction(rawDo.get(i), where + ".do[" + i + "]", on, declared));
        }
        return new Plugin.Rule(on, when, actions);
    }

    private static Plugin.Condition parseCondition(Object raw, String where, PluginEvent.Type on, Set<Permission> declared) throws PluginException {
        Map<String, Object> o = object(raw, where);
        String typeId = string(o.get("type"), where + ".type", 1, 32);
        Plugin.ConditionType type = Plugin.ConditionType.byId(typeId);
        if (type == null) {
            throw new PluginException(where + ": unknown condition \"" + typeId + "\"");
        }
        if (on != PluginEvent.Type.MESSAGE_RECEIVED) {
            throw new PluginException(where + ": conditions are only available for message_received");
        }
        if (type.readsText && !declared.contains(Permission.READ_MESSAGES)) {
            throw new PluginException(where + ": \"" + typeId + "\" needs the \"read_messages\" permission");
        }
        switch (type) {
            case TEXT_CONTAINS:
            case TEXT_STARTS_WITH:
            case TEXT_EQUALS: {
                keys(o, where, set("type", "value", "ignore_case"), set("type", "value"));
                String value = string(o.get("value"), where + ".value", 1, MAX_TEXT);
                boolean ignoreCase = !o.containsKey("ignore_case") || bool(o.get("ignore_case"), where + ".ignore_case");
                return new Plugin.Condition(type, value, ignoreCase, null, 0);
            }
            case CHAT_KIND: {
                keys(o, where, set("type", "value"), set("type", "value"));
                String kind = string(o.get("value"), where + ".value", 1, 16);
                PluginEvent.ChatKind chatKind = PluginEvent.ChatKind.byId(kind);
                if (chatKind == null || chatKind == PluginEvent.ChatKind.UNKNOWN) {
                    throw new PluginException(where + ": \"value\" must be private, group or channel");
                }
                return new Plugin.Condition(type, null, false, chatKind, 0);
            }
            default: { // FROM_USER
                keys(o, where, set("type", "value"), set("type", "value"));
                long userId = integer(o.get("value"), where + ".value");
                if (userId <= 0) {
                    throw new PluginException(where + ": user id must be positive");
                }
                return new Plugin.Condition(type, null, false, null, userId);
            }
        }
    }

    private static Plugin.Action parseAction(Object raw, String where, PluginEvent.Type on, Set<Permission> declared) throws PluginException {
        Map<String, Object> o = object(raw, where);
        String typeId = string(o.get("type"), where + ".type", 1, 32);
        Plugin.ActionType type = Plugin.ActionType.byId(typeId);
        if (type == null) {
            throw new PluginException(where + ": unknown action \"" + typeId + "\"");
        }
        if (!declared.contains(type.permission)) {
            throw new PluginException(where + ": \"" + typeId + "\" needs the \"" + type.permission.id + "\" permission");
        }
        switch (type) {
            case TOAST: {
                keys(o, where, set("type", "text"), set("type", "text"));
                String text = string(o.get("text"), where + ".text", 1, MAX_TEXT);
                if (text.contains("{text}")) {
                    if (on != PluginEvent.Type.MESSAGE_RECEIVED) {
                        throw new PluginException(where + ": {text} is only available for message_received");
                    }
                    if (!declared.contains(Permission.READ_MESSAGES)) {
                        throw new PluginException(where + ": {text} needs the \"read_messages\" permission");
                    }
                }
                return new Plugin.Action(type, text, 0);
            }
            case VIBRATE: {
                keys(o, where, set("type", "ms"), set("type", "ms"));
                long ms = integer(o.get("ms"), where + ".ms");
                if (ms < 1 || ms > MAX_VIBRATE_MS) {
                    throw new PluginException(where + ": \"ms\" must be 1.." + MAX_VIBRATE_MS);
                }
                return new Plugin.Action(type, null, (int) ms);
            }
            default: { // MUTE_CHAT
                if (on != PluginEvent.Type.MESSAGE_RECEIVED) {
                    throw new PluginException(where + ": mute_chat is only available for message_received");
                }
                keys(o, where, set("type", "minutes"), set("type", "minutes"));
                long minutes = integer(o.get("minutes"), where + ".minutes");
                if (minutes < 1 || minutes > MAX_MUTE_MINUTES) {
                    throw new PluginException(where + ": \"minutes\" must be 1.." + MAX_MUTE_MINUTES);
                }
                return new Plugin.Action(type, null, (int) minutes);
            }
        }
    }

    // ---- small typed helpers: every wrong shape becomes a readable PluginException ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object v, String where) throws PluginException {
        if (!(v instanceof Map)) {
            throw new PluginException(where + " must be an object");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v, String where, int max) throws PluginException {
        if (!(v instanceof List)) {
            throw new PluginException(where + " must be a list");
        }
        List<Object> l = (List<Object>) v;
        if (l.size() > max) {
            throw new PluginException(where + " has too many items (max " + max + ")");
        }
        return l;
    }

    private static String string(Object v, String where, int min, int max) throws PluginException {
        if (!(v instanceof String)) {
            throw new PluginException(where + " must be a string");
        }
        String s = (String) v;
        if (s.length() < min || s.length() > max) {
            throw new PluginException(where + " must be " + min + ".." + max + " characters long");
        }
        return s;
    }

    private static long integer(Object v, String where) throws PluginException {
        if (!(v instanceof Long)) {
            throw new PluginException(where + " must be a whole number");
        }
        return (Long) v;
    }

    private static boolean bool(Object v, String where) throws PluginException {
        if (!(v instanceof Boolean)) {
            throw new PluginException(where + " must be true or false");
        }
        return (Boolean) v;
    }

    private static void keys(Map<String, Object> o, String where, Set<String> allowed, Set<String> required) throws PluginException {
        for (String k : o.keySet()) {
            if (!allowed.contains(k)) {
                throw new PluginException(where + ": unknown field \"" + k + "\"");
            }
        }
        for (String k : required) {
            if (!o.containsKey(k)) {
                throw new PluginException(where + ": missing field \"" + k + "\"");
            }
        }
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
