package org.telegram.messenger.timegram.plugins;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A TimeGram plugin file (<code>.pg</code>): a few header lines, then Lua code.
 * <pre>
 * --@id night.quiet
 * --@name Night quiet
 * --@version 1.0
 * --@description Mutes noisy chats at night
 * --@permissions read_messages, mute_chats
 *
 * function on_message(msg)
 *   if msg.text and msg.text:find("sale") then tg.mute_chat(msg.dialog_id, 60) end
 * end
 * </pre>
 * The header is read as plain text before any code runs, so the user sees the permissions first.
 * The header must be at the very top of the file; unknown or repeated keys are errors.
 */
public final class PgManifest {

    public static final int MAX_FILE_CHARS = 128 * 1024;
    public static final String EXTENSION = ".pg";

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,47}");
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList("id", "name", "version", "description", "permissions"));

    public final String id;
    public final String name;
    public final String version;
    public final String description;
    public final Set<Permission> permissions;
    /** Whole file text (header lines stay as comments, so Lua line numbers match the file). */
    public final String source;

    private PgManifest(String id, String name, String version, String description, Set<Permission> permissions, String source) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.description = description;
        this.permissions = permissions;
        this.source = source;
    }

    public static PgManifest parse(String text) throws PluginException {
        if (text == null || text.isEmpty()) {
            throw new PluginException("The file is empty");
        }
        if (text.length() > MAX_FILE_CHARS) {
            throw new PluginException("The file is too big (max " + (MAX_FILE_CHARS / 1024) + " KB)");
        }
        if (text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        // compiled Lua starts with ESC; only plain text is accepted
        if (text.indexOf('\u001b') >= 0 || text.indexOf('\0') >= 0) {
            throw new PluginException("The file must be plain text");
        }

        String id = null, name = null, version = null, description = "";
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        Set<String> seen = new HashSet<>();

        String[] lines = text.split("\r?\n", -1);
        int i = 0;
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("--@")) {
                break;
            }
            int space = line.indexOf(' ');
            String key = (space < 0 ? line.substring(3) : line.substring(3, space)).trim();
            String value = space < 0 ? "" : line.substring(space + 1).trim();
            if (!KEYS.contains(key)) {
                throw new PluginException("Header line " + (i + 1) + ": unknown key \"" + key + "\"");
            }
            if (!seen.add(key)) {
                throw new PluginException("Header line " + (i + 1) + ": \"" + key + "\" is repeated");
            }
            switch (key) {
                case "id":
                    if (!ID.matcher(value).matches()) {
                        throw new PluginException("\"id\" must be 3-48 characters: a-z, 0-9, '.', '_' or '-'");
                    }
                    id = value;
                    break;
                case "name":
                    name = length(value, "name", 1, 40);
                    break;
                case "version":
                    version = length(value, "version", 1, 16);
                    break;
                case "description":
                    description = length(value, "description", 0, 200);
                    break;
                default: // permissions
                    if (!value.isEmpty()) {
                        for (String part : value.split(",", -1)) {
                            String pid = part.trim();
                            Permission p = Permission.byId(pid);
                            if (p == null) {
                                throw new PluginException("Unknown permission \"" + pid + "\"");
                            }
                            if (!permissions.add(p)) {
                                throw new PluginException("Permission \"" + pid + "\" is listed twice");
                            }
                        }
                    }
            }
        }
        if (id == null || name == null || version == null) {
            throw new PluginException("The file must start with --@id, --@name and --@version lines");
        }
        return new PgManifest(id, name, version, description, permissions, text);
    }

    private static String length(String v, String what, int min, int max) throws PluginException {
        if (v.length() < min || v.length() > max) {
            throw new PluginException("\"" + what + "\" must be " + min + ".." + max + " characters long");
        }
        return v;
    }
}
