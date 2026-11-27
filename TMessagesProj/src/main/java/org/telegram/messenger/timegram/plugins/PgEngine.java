package org.telegram.messenger.timegram.plugins;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.MathLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Runs <code>.pg</code> (Lua) plugins in a sandbox.
 * <p>
 * Safety layers, from the outside in:
 * <ol>
 * <li><b>Closed API.</b> A script sees only its own globals, the safe parts of the standard library and the
 * {@code tg} table built here. There is no io, os, debug, require, load, coroutine or Java access, and
 * nothing in {@link PgHost} touches the session, Stars, NFT gifts or payments.</li>
 * <li><b>Permissions.</b> {@code tg} functions the user did not grant exist only as stubs that raise an error.</li>
 * <li><b>Limits per call.</b> Instruction count, wall-clock time, string length (patched into LuaJ), argument
 * sizes for pattern functions, a hard timeout for a stuck script.</li>
 * <li><b>Limits per minute.</b> Per-function rate limits and a CPU-time budget per plugin.</li>
 * <li><b>Strikes.</b> Hitting limits repeatedly switches the plugin off until the user turns it on again.</li>
 * <li><b>No recursion.</b> Only the client reports events, never a plugin. An event that arrives from inside
 * a running plugin (on the plugin's own thread) is dropped, outgoing messages are never reported, and a
 * plugin cannot call another plugin.</li>
 * </ol>
 */
public final class PgEngine {

    public static final int INSTRUCTIONS_PER_CALL = 100_000;
    public static final int INSTRUCTIONS_AT_LOAD = 200_000;
    public static final long NANOS_PER_CALL = 250_000_000L;
    public static final long NANOS_PER_MINUTE = 3_000_000_000L;
    public static final int MAX_STRIKES = 3;
    public static final int MAX_ERROR_STREAK = 10;

    public static final int MAX_STORAGE_KEYS = 32;
    public static final int MAX_STORAGE_VALUE = 1024;
    public static final int MAX_STORAGE_TOTAL = 16 * 1024;
    public static final int MAX_MESSAGE_TEXT = 4096;
    public static final int MAX_LOG = 200;

    private static final int HOOK_STEP = 100;
    private static final long WINDOW_MS = 60_000;
    private static final Pattern STORAGE_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    public interface Listener {
        /** The engine switched a plugin off. Called on the plugin thread: post to the UI thread. */
        void onPluginDisabled(String pluginId, String reason);

        /** Plugin storage changed and should be saved. Called on the plugin thread. */
        void onStorageChanged(String pluginId, Map<String, String> data);
    }

    private enum Cat {
        LOG(60), STORAGE(120), TOAST(20), VIBRATE(20), MUTE(30), SEND(10), DELETE(20), READ(60);

        final int perMinute;

        Cat(int perMinute) {
            this.perMinute = perMinute;
        }
    }

    /** Java Error on purpose: Lua's pcall cannot catch it, so a script cannot swallow its own limit. */
    private static final class LimitError extends Error {
        LimitError(String message) {
            super(message, null, false, false);
        }
    }

    private static final class Entry {
        final PgManifest manifest;
        final Set<Permission> granted;
        Globals globals;
        LuaValue sethook;
        boolean loaded;
        boolean enabled;
        int strikes;
        int errorStreak;
        String disabledReason;
        String lastError;
        final Map<String, String> storage = new LinkedHashMap<>();
        final EnumMap<Cat, ArrayDeque<Long>> rates = new EnumMap<>(Cat.class);
        final ArrayDeque<long[]> cpu = new ArrayDeque<>(); // {time, nanos}

        Entry(PgManifest manifest, Set<Permission> granted) {
            this.manifest = manifest;
            this.granted = granted;
            for (Cat c : Cat.values()) {
                rates.put(c, new ArrayDeque<Long>());
            }
        }
    }

    private final PgHost host;
    private final PluginEngine.Clock clock;
    private final Map<String, Entry> plugins = new LinkedHashMap<>();
    private Listener listener;
    private final AtomicInteger droppedReentrant = new AtomicInteger();
    /** the Java thread that is running plugin code right now (null when none) */
    private volatile Thread worker;

    public PgEngine(PgHost host, PluginEngine.Clock clock) {
        this.host = host;
        this.clock = clock;
        initShared();
    }

    // ------------------------------------------------------------------ shared, read-only Lua state

    private static Globals serverGlobals;
    private static LuaTable sharedString;

    /**
     * Lua keeps ONE string metatable for the whole JVM, so it must never be changeable by a script.
     * It is replaced once by a read-only copy whose string library has size-limited functions.
     */
    private static synchronized void initShared() {
        if (serverGlobals != null) {
            return;
        }
        Globals sg = new Globals();
        sg.load(new BaseLib());
        sg.load(new StringLib());
        sg.load(new MathLib());
        LoadState.install(sg);
        LuaC.install(sg);

        LuaTable string = (LuaTable) sg.get("string");
        LuaValue rep = string.get("rep");
        string.rawset("rep", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                int n = a.checkint(2);
                int len = a.checkstring(1).length();
                int sep = a.optstring(3, LuaValue.EMPTYSTRING).length();
                if (n > 0 && (long) len * n + (long) sep * (n - 1) > LuaString.TIMEGRAM_MAX_LENGTH) {
                    throw new LuaError("string too long (max " + LuaString.TIMEGRAM_MAX_LENGTH + ")");
                }
                return rep.invoke(a);
            }
        });
        string.rawset("dump", LuaValue.NIL);
        for (String fn : new String[]{"find", "match", "gmatch", "gsub"}) {
            string.rawset(fn, new PatternGuard(string.get(fn), fn.equals("gsub")));
        }

        LuaString.s_metatable = new ReadOnlyTable(LuaValue.tableOf(new LuaValue[]{LuaValue.INDEX, string}));
        sharedString = (LuaTable) LuaString.s_metatable.get(LuaValue.INDEX);
        serverGlobals = sg;
    }

    /** Subject, pattern and replacement sizes are limited: pattern matching is slow in the worst case. */
    private static final class PatternGuard extends VarArgFunction {
        final LuaValue f;
        final boolean gsub;

        PatternGuard(LuaValue f, boolean gsub) {
            this.f = f;
            this.gsub = gsub;
        }

        @Override
        public Varargs invoke(Varargs a) {
            if (a.arg(1).isstring() && a.arg(1).checkstring().length() > 4096) {
                throw new LuaError("string is too long for pattern matching (max 4096)");
            }
            if (a.arg(2).isstring() && a.arg(2).checkstring().length() > 200) {
                throw new LuaError("pattern is too long (max 200)");
            }
            if (gsub) {
                if (a.arg(3).isstring() && a.arg(3).checkstring().length() > 256) {
                    throw new LuaError("replacement is too long (max 256)");
                }
                return f.invoke(LuaValue.varargsOf(new LuaValue[]{a.arg(1), a.arg(2), a.arg(3), LuaValue.valueOf(Math.min(a.optint(4, 256), 256))}));
            }
            return f.invoke(a);
        }
    }

    /** A table nobody can change (copied recursively, then locked). */
    private static final class ReadOnlyTable extends LuaTable {
        private boolean locked;

        ReadOnlyTable(LuaValue table) {
            presize(table.length(), 0);
            for (Varargs n = table.next(LuaValue.NIL); !n.arg1().isnil(); n = table.next(n.arg1())) {
                LuaValue value = n.arg(2);
                super.rawset(n.arg1(), value.istable() ? new ReadOnlyTable(value) : value);
            }
            locked = true;
        }

        private void deny() {
            if (locked) {
                error("table is read-only");
            }
        }

        @Override
        public LuaValue setmetatable(LuaValue metatable) {
            deny();
            return this;
        }

        @Override
        public void set(int key, LuaValue value) {
            deny();
            super.set(key, value);
        }

        @Override
        public void set(LuaValue key, LuaValue value) {
            deny();
            super.set(key, value);
        }

        @Override
        public void rawset(int key, LuaValue value) {
            deny();
            super.rawset(key, value);
        }

        @Override
        public void rawset(LuaValue key, LuaValue value) {
            deny();
            super.rawset(key, value);
        }

        @Override
        public LuaValue remove(int pos) {
            deny();
            return super.remove(pos);
        }

        @Override
        public void insert(int pos, LuaValue value) {
            deny();
            super.insert(pos, value);
        }

        @Override
        public void hashset(LuaValue key, LuaValue value) {
            deny();
            super.hashset(key, value);
        }

        @Override
        public void sort(LuaValue comparator) {
            deny();
            super.sort(comparator);
        }
    }

    // ------------------------------------------------------------------ public API

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Installs a plugin: compiles it (syntax errors throw) and, if enabled, runs its top level once.
     * Only permissions the plugin declared can be granted.
     */
    public synchronized void install(PgManifest manifest, Set<Permission> granted, boolean enabled) throws PluginException {
        Set<Permission> effective = EnumSet.noneOf(Permission.class);
        for (Permission p : granted) {
            if (manifest.permissions.contains(p)) {
                effective.add(p);
            }
        }
        Entry e = new Entry(manifest, effective);
        e.enabled = enabled;
        buildGlobals(e);
        plugins.put(manifest.id, e);
        if (enabled) {
            try {
                ensureLoaded(e);
            } catch (PluginException ex) {
                plugins.remove(manifest.id);
                throw ex;
            }
        }
    }

    public synchronized void remove(String pluginId) {
        plugins.remove(pluginId);
    }

    /** The user turns a plugin on or off. Turning it on clears strikes and errors. */
    public synchronized void setEnabled(String pluginId, boolean enabled) throws PluginException {
        Entry e = plugins.get(pluginId);
        if (e == null) {
            return;
        }
        e.enabled = enabled;
        if (enabled) {
            e.strikes = 0;
            e.errorStreak = 0;
            e.disabledReason = null;
            e.cpu.clear();
            for (ArrayDeque<Long> q : e.rates.values()) {
                q.clear();
            }
            ensureLoaded(e);
        }
    }

    public synchronized boolean isEnabled(String pluginId) {
        Entry e = plugins.get(pluginId);
        return e != null && e.enabled;
    }

    public synchronized String getDisabledReason(String pluginId) {
        Entry e = plugins.get(pluginId);
        return e == null ? null : e.disabledReason;
    }

    /** Last error raised by the plugin's code, or null. */
    public synchronized String getLastError(String pluginId) {
        Entry e = plugins.get(pluginId);
        return e == null ? null : e.lastError;
    }

    public int getDroppedReentrantEvents() {
        return droppedReentrant.get();
    }

    public synchronized Map<String, String> getStorage(String pluginId) {
        Entry e = plugins.get(pluginId);
        return e == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(e.storage);
    }

    /** Restores saved storage (before the plugin runs). Values are re-validated. */
    public synchronized void setStorage(String pluginId, Map<String, String> data) {
        Entry e = plugins.get(pluginId);
        if (e == null || data == null) {
            return;
        }
        e.storage.clear();
        int total = 0;
        for (Map.Entry<String, String> kv : data.entrySet()) {
            String k = kv.getKey(), v = kv.getValue();
            if (k == null || v == null || !STORAGE_KEY.matcher(k).matches() || v.length() > MAX_STORAGE_VALUE
                    || e.storage.size() >= MAX_STORAGE_KEYS || total + k.length() + v.length() > MAX_STORAGE_TOTAL) {
                continue;
            }
            total += k.length() + v.length();
            e.storage.put(k, v);
        }
    }

    public synchronized List<PgManifest> getPlugins() {
        List<PgManifest> list = new ArrayList<>();
        for (Entry e : plugins.values()) {
            list.add(e.manifest);
        }
        return list;
    }

    /** An incoming (or any) message was received. Outgoing messages are ignored. Returns how many handlers ran. */
    public int dispatchMessage(PgHost.Message message) {
        if (message == null || message.out) {
            return 0;
        }
        return dispatch("on_message", message);
    }

    public int dispatchAppOpened() {
        return dispatch("on_app_open", null);
    }

    // ------------------------------------------------------------------ dispatch

    private int dispatch(String handler, PgHost.Message message) {
        // Recursion guard: an event arriving from inside a running plugin (its own thread) is refused.
        // It is checked before taking the lock, because the plugin thread never owns it.
        if (Thread.currentThread() == worker) {
            droppedReentrant.incrementAndGet();
            return 0;
        }
        int ran = 0;
        synchronized (this) {
            for (Entry e : new ArrayList<>(plugins.values())) {
                if (!e.enabled || !e.loaded) {
                    continue;
                }
                LuaValue fn = e.globals.get(handler);
                if (!fn.isfunction()) {
                    continue;
                }
                if (!takeCpu(e)) {
                    continue;
                }
                Varargs args = message == null ? LuaValue.NONE : messageTable(e, message);
                runGuarded(e, fn, args, INSTRUCTIONS_PER_CALL);
                ran++;
            }
        }
        return ran;
    }

    /** CPU budget: rolling one minute of script time. */
    private boolean takeCpu(Entry e) {
        long now = clock.now();
        long sum = 0;
        while (!e.cpu.isEmpty() && now - e.cpu.peekFirst()[0] >= WINDOW_MS) {
            e.cpu.pollFirst();
        }
        for (long[] c : e.cpu) {
            sum += c[1];
        }
        if (sum >= NANOS_PER_MINUTE) {
            strike(e, "Uses too much CPU time");
            return false;
        }
        return true;
    }

    private void ensureLoaded(Entry e) throws PluginException {
        if (e.loaded) {
            return;
        }
        LuaValue chunk;
        try {
            chunk = serverGlobals.load(e.manifest.source, "=" + e.manifest.id, e.globals);
        } catch (LuaError ex) {
            throw new PluginException("Lua error: " + ex.getMessage());
        }
        String error = runGuarded(e, chunk, LuaValue.NONE, INSTRUCTIONS_AT_LOAD);
        if (error != null) {
            throw new PluginException("The plugin failed to start: " + error);
        }
        e.loaded = true;
    }

    /** Runs a function in the sandbox. Returns an error text or null. Never throws. */
    private String runGuarded(final Entry e, LuaValue fn, Varargs args, int instructions) {
        final LuaValue target = fn;
        LuaThread thread = new LuaThread(e.globals, new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                worker = Thread.currentThread();
                try {
                    return target.invoke(a);
                } catch (StackOverflowError so) {
                    // LuaJ would otherwise report success when a script overflows the stack
                    throw new LuaError("pg-limit: stack overflow");
                } finally {
                    worker = null;
                }
            }
        });
        final long start = System.nanoTime();
        final long deadline = start + NANOS_PER_CALL;
        final int maxHooks = Math.max(1, instructions / HOOK_STEP);
        final int[] hooks = {0};
        LuaValue hook = new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                if (++hooks[0] > maxHooks) {
                    throw new LimitError("pg-limit: instruction limit");
                }
                if (System.nanoTime() > deadline) {
                    throw new LimitError("pg-limit: time limit");
                }
                return NIL;
            }
        };
        e.sethook.invoke(LuaValue.varargsOf(new LuaValue[]{thread, hook, LuaValue.EMPTYSTRING, LuaValue.valueOf(HOOK_STEP)}));

        String error = null;
        try {
            Varargs r = thread.resume(args);
            if (!r.arg1().toboolean()) {
                error = r.arg(2).isnil() ? "error" : r.arg(2).tojstring();
            }
        } catch (Throwable t) {
            error = "pg-limit: " + t;
        }
        long used = System.nanoTime() - start;
        e.cpu.addLast(new long[]{clock.now(), used});

        if (error == null) {
            e.errorStreak = 0;
            return null;
        }
        e.lastError = error;
        if (error.startsWith("pg-limit:")) {
            strike(e, "Hit a safety limit (" + error.substring(9).trim() + ")");
        } else if (++e.errorStreak >= MAX_ERROR_STREAK) {
            disable(e, "Too many errors in a row");
        }
        return error;
    }

    private void strike(Entry e, String reason) {
        if (++e.strikes >= MAX_STRIKES) {
            disable(e, reason);
        }
    }

    private void disable(Entry e, String reason) {
        if (!e.enabled) {
            return;
        }
        e.enabled = false;
        e.disabledReason = reason;
        Listener l = listener;
        if (l != null) {
            try {
                l.onPluginDisabled(e.manifest.id, reason);
            } catch (Throwable ignore) {
            }
        }
    }

    // ------------------------------------------------------------------ the sandbox

    private void buildGlobals(Entry e) {
        Globals g = new Globals();
        g.load(new BaseLib());
        g.load(new Bit32Lib());
        g.load(new TableLib());
        g.load(new MathLib());
        // The debug library is needed only for the instruction hook; it is taken out of reach at once.
        g.load(new DebugLib());
        e.sethook = g.get("debug").get("sethook");
        g.set("debug", LuaValue.NIL);
        for (String name : new String[]{"dofile", "loadfile", "load", "collectgarbage", "print"}) {
            g.set(name, LuaValue.NIL);
        }
        g.set("string", sharedString);
        g.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= a.narg() && sb.length() < MAX_LOG; i++) {
                    if (i > 1) {
                        sb.append('\t');
                    }
                    sb.append(a.arg(i).tojstring());
                }
                log(e, sb.toString());
                return NONE;
            }
        });
        // table.unpack can allocate a huge array in one call
        final LuaTable table = (LuaTable) g.get("table");
        final LuaValue unpack = table.get("unpack");
        table.set("unpack", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                int i = a.optint(2, 1);
                int j = a.arg(3).isnil() ? a.arg1().checktable().length() : a.checkint(3);
                if ((long) j - i >= 256) {
                    throw new LuaError("too many values to unpack (max 256)");
                }
                return unpack.invoke(a);
            }
        });
        g.set("tg", buildApi(e));
        e.globals = g;
    }

    // ------------------------------------------------------------------ the tg API

    private abstract class Fn extends VarArgFunction {
    }

    private LuaTable buildApi(final Entry e) {
        final LuaTable tg = new LuaTable();
        tg.set("plugin_id", LuaValue.valueOf(e.manifest.id));

        tg.set("now", new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf((double) (clock.now() / 1000));
            }
        });
        tg.set("log", new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                log(e, a.checkjstring(1));
                return NONE;
            }
        });
        tg.set("storage_get", new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                rate(e, Cat.STORAGE);
                String v = e.storage.get(a.checkjstring(1));
                return v == null ? NIL : LuaValue.valueOf(v);
            }
        });
        tg.set("storage_set", new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                rate(e, Cat.STORAGE);
                String key = a.checkjstring(1);
                if (!STORAGE_KEY.matcher(key).matches()) {
                    throw new LuaError("bad storage key (1-32 chars: a-z A-Z 0-9 _ . -)");
                }
                if (a.arg(2).isnil()) {
                    e.storage.remove(key);
                } else {
                    String value = a.checkjstring(2);
                    if (value.length() > MAX_STORAGE_VALUE) {
                        throw new LuaError("storage value is too long (max " + MAX_STORAGE_VALUE + ")");
                    }
                    int total = key.length() + value.length();
                    for (Map.Entry<String, String> kv : e.storage.entrySet()) {
                        if (!kv.getKey().equals(key)) {
                            total += kv.getKey().length() + kv.getValue().length();
                        }
                    }
                    if ((!e.storage.containsKey(key) && e.storage.size() >= MAX_STORAGE_KEYS) || total > MAX_STORAGE_TOTAL) {
                        throw new LuaError("plugin storage is full");
                    }
                    e.storage.put(key, value);
                }
                Listener l = listener;
                if (l != null) {
                    try {
                        l.onStorageChanged(e.manifest.id, new LinkedHashMap<>(e.storage));
                    } catch (Throwable ignore) {
                    }
                }
                return NONE;
            }
        });

        tg.set("toast", gate(e, Permission.TOAST, new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                String text = limited(a.checkjstring(1), 200, "toast text");
                rate(e, Cat.TOAST);
                try {
                    host.showToast(text);
                } catch (Throwable t) {
                    throw new LuaError("host error");
                }
                return NONE;
            }
        }));
        tg.set("vibrate", gate(e, Permission.VIBRATE, new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                int ms = a.checkint(1);
                if (ms < 1 || ms > 500) {
                    throw new LuaError("vibrate: ms must be 1..500");
                }
                rate(e, Cat.VIBRATE);
                try {
                    host.vibrate(ms);
                } catch (Throwable t) {
                    throw new LuaError("host error");
                }
                return NONE;
            }
        }));
        tg.set("mute_chat", gate(e, Permission.MUTE_CHATS, new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                long dialogId = a.checklong(1);
                int minutes = a.checkint(2);
                if (minutes < 1 || minutes > 24 * 60) {
                    throw new LuaError("mute_chat: minutes must be 1..1440");
                }
                rate(e, Cat.MUTE);
                try {
                    host.muteChat(dialogId, minutes);
                } catch (Throwable t) {
                    throw new LuaError("host error");
                }
                return NONE;
            }
        }));
        tg.set("send_message", gate(e, Permission.SEND_MESSAGES, new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                long dialogId = a.checklong(1);
                String text = a.checkjstring(2);
                if (text.isEmpty() || text.length() > MAX_MESSAGE_TEXT) {
                    throw new LuaError("send_message: text must be 1.." + MAX_MESSAGE_TEXT + " characters");
                }
                rate(e, Cat.SEND);
                try {
                    host.sendMessage(dialogId, text);
                } catch (Throwable t) {
                    throw new LuaError("host error");
                }
                return NONE;
            }
        }));
        tg.set("delete_message", gate(e, Permission.DELETE_MESSAGES, new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                long dialogId = a.checklong(1);
                long messageId = a.checklong(2);
                rate(e, Cat.DELETE);
                try {
                    host.deleteMessage(dialogId, messageId);
                } catch (Throwable t) {
                    throw new LuaError("host error");
                }
                return NONE;
            }
        }));
        tg.set("get_chats", gate(e, Permission.READ_CHATS, new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                int limit = Math.max(1, Math.min(a.optint(1, 50), 100));
                rate(e, Cat.READ);
                List<PgHost.Chat> chats;
                try {
                    chats = host.getChats(limit);
                } catch (Throwable t) {
                    throw new LuaError("host error");
                }
                LuaTable out = new LuaTable();
                int n = 0;
                if (chats != null) {
                    for (PgHost.Chat c : chats) {
                        if (n >= limit) {
                            break;
                        }
                        LuaTable t = new LuaTable();
                        t.set("id", LuaValue.valueOf((double) c.id));
                        t.set("title", LuaValue.valueOf(cut(c.title, 100)));
                        t.set("kind", LuaValue.valueOf(c.kind.id));
                        t.set("unread", LuaValue.valueOf(c.unread));
                        out.set(++n, t);
                    }
                }
                return out;
            }
        }));
        tg.set("get_messages", gate(e, Permission.READ_MESSAGES, new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                long dialogId = a.checklong(1);
                int limit = Math.max(1, Math.min(a.optint(2, 20), 50));
                rate(e, Cat.READ);
                List<PgHost.Message> messages;
                try {
                    messages = host.getMessages(dialogId, limit);
                } catch (Throwable t) {
                    throw new LuaError("host error");
                }
                LuaTable out = new LuaTable();
                int n = 0;
                if (messages != null) {
                    for (PgHost.Message m : messages) {
                        if (n >= limit) {
                            break;
                        }
                        out.set(++n, messageTable(e, m));
                    }
                }
                return out;
            }
        }));
        return tg;
    }

    /** A function the user did not grant: it exists, but only raises an error and never reaches the host. */
    private LuaValue gate(final Entry e, final Permission permission, final LuaValue impl) {
        if (e.granted.contains(permission)) {
            return impl;
        }
        return new Fn() {
            @Override
            public Varargs invoke(Varargs a) {
                throw new LuaError("permission not granted: " + permission.id);
            }
        };
    }

    private LuaTable messageTable(Entry e, PgHost.Message m) {
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf((double) m.id));
        t.set("dialog_id", LuaValue.valueOf((double) m.dialogId));
        t.set("from_id", LuaValue.valueOf((double) m.fromId));
        t.set("date", LuaValue.valueOf((double) m.date));
        t.set("kind", LuaValue.valueOf(m.kind.id));
        t.set("out", LuaValue.valueOf(m.out));
        if (m.text != null && e.granted.contains(Permission.READ_MESSAGES)) {
            t.set("text", LuaValue.valueOf(cut(m.text, MAX_MESSAGE_TEXT)));
        }
        return t;
    }

    private void log(Entry e, String text) {
        ArrayDeque<Long> q = e.rates.get(Cat.LOG);
        long now = clock.now();
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) {
            q.pollFirst();
        }
        if (q.size() >= Cat.LOG.perMinute) {
            return; // logs are dropped silently, no strike
        }
        q.addLast(now);
        try {
            host.log(e.manifest.id, cut(text, MAX_LOG));
        } catch (Throwable ignore) {
        }
    }

    /** Per-minute limit of one function family. Over the limit: error for the script and a strike. */
    private void rate(Entry e, Cat cat) {
        if (!e.enabled) {
            throw new LimitError("pg-limit: plugin is switched off");
        }
        ArrayDeque<Long> q = e.rates.get(cat);
        long now = clock.now();
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) {
            q.pollFirst();
        }
        if (q.size() >= cat.perMinute) {
            strike(e, "Called " + cat.name().toLowerCase() + " too often");
            throw new LuaError("rate limit: " + cat.name().toLowerCase() + " (max " + cat.perMinute + " per minute)");
        }
        q.addLast(now);
    }

    private static String limited(String s, int max, String what) {
        if (s.length() > max) {
            throw new LuaError(what + " is too long (max " + max + ")");
        }
        return s;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
