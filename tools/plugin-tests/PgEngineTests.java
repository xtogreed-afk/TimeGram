package org.telegram.messenger.timegram.plugins;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host tests for the Lua (.pg) sandbox. Most of them are attacks: a hostile script tries to escape, hang,
 * eat memory, loop through the client, flood, or reach the session / Stars / NFT / payments.
 */
public class PgEngineTests {

    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    static class FakeHost implements PgHost {
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        PgEngine engine;
        boolean reenter;

        public void log(String id, String text) {
            log.add("log:" + text);
        }

        public void showToast(String text) {
            log.add("toast:" + text);
            if (reenter && engine != null) {
                // the client reacting to our action by reporting a new message, on the plugin's own thread
                engine.dispatchMessage(msg("sale"));
            }
        }

        public void vibrate(int ms) {
            log.add("vibrate:" + ms);
        }

        public void muteChat(long d, int m) {
            log.add("mute:" + d + ":" + m);
        }

        public void sendMessage(long d, String t) {
            log.add("send:" + d + ":" + t);
        }

        public void deleteMessage(long d, long m) {
            log.add("delete:" + d + ":" + m);
        }

        public List<Chat> getChats(int limit) {
            List<Chat> l = new ArrayList<>();
            l.add(new Chat(1, "Chat one", PluginEvent.ChatKind.GROUP, 3));
            return l;
        }

        public List<Message> getMessages(long d, int limit) {
            List<Message> l = new ArrayList<>();
            l.add(new Message(5, d, 9, 1000, PluginEvent.ChatKind.GROUP, false, "old message"));
            return l;
        }

        int count(String prefix) {
            int n = 0;
            synchronized (log) {
                for (String s : log) {
                    if (s.startsWith(prefix)) {
                        n++;
                    }
                }
            }
            return n;
        }

        boolean has(String text) {
            return log.contains(text);
        }
    }

    static long fakeNow;

    static PgHost.Message msg(String text) {
        return new PgHost.Message(1, 42, 7, 1000, PluginEvent.ChatKind.GROUP, false, text);
    }

    static PgManifest pg(String perms, String body) throws PluginException {
        return PgManifest.parse("--@id test.pg\n--@name T\n--@version 1\n--@permissions " + perms + "\n" + body);
    }

    static class Env {
        final FakeHost host = new FakeHost();
        final PgEngine engine;
        final List<String> disabled = Collections.synchronizedList(new ArrayList<String>());

        Env() {
            fakeNow = 1_000_000;
            engine = new PgEngine(host, () -> fakeNow);
            host.engine = engine;
            engine.setListener(new PgEngine.Listener() {
                public void onPluginDisabled(String id, String reason) {
                    disabled.add(id);
                }

                public void onStorageChanged(String id, Map<String, String> data) {
                    host.log.add("saved:" + data.size());
                }
            });
        }

        void install(String perms, String body) throws PluginException {
            install(pg(perms, body), EnumSet.allOf(Permission.class));
        }

        void install(PgManifest m, Set<Permission> grant) throws PluginException {
            engine.install(m, grant, true);
        }
    }

    static boolean manifestRejects(String text, String contains) {
        try {
            PgManifest.parse(text);
            return false;
        } catch (PluginException e) {
            return contains == null || e.getMessage().contains(contains);
        }
    }

    static boolean installRejects(String perms, String body, String contains) {
        try {
            new Env().install(perms, body);
            return false;
        } catch (PluginException e) {
            return contains == null || e.getMessage().contains(contains);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Manifest (.pg header):");
        PgManifest ok = pg("toast, read_messages", "function on_message(m) end");
        check("valid file parses", ok.id.equals("test.pg") && ok.permissions.contains(Permission.TOAST) && ok.permissions.size() == 2);
        check("empty file rejected", manifestRejects("", "empty"));
        check("header required", manifestRejects("print('hi')", "must start with"));
        check("header must be at the very top", manifestRejects("\n--@id test.pg\n--@name T\n--@version 1\n", "must start with"));
        check("unknown header key rejected", manifestRejects("--@id test.pg\n--@name T\n--@version 1\n--@evil yes\n", "unknown key"));
        check("repeated header key rejected", manifestRejects("--@id test.pg\n--@id other.pg\n--@name T\n--@version 1\n", "repeated"));
        check("unknown permission rejected", manifestRejects("--@id test.pg\n--@name T\n--@version 1\n--@permissions root\n", "Unknown permission"));
        check("no permission for session/stars/nft/payments exists",
                Permission.byId("session") == null && Permission.byId("stars") == null && Permission.byId("nft") == null && Permission.byId("payments") == null);
        check("bad id rejected", manifestRejects("--@id ../x\n--@name T\n--@version 1\n", "id"));
        check("compiled Lua (ESC byte) rejected", manifestRejects("--@id test.pg\n--@name T\n--@version 1\n\u001bLua", "plain text"));
        check("huge file rejected", manifestRejects("--@id test.pg\n--@name T\n--@version 1\n--" + repeat("x", PgManifest.MAX_FILE_CHARS), "too big"));

        System.out.println("Basics:");
        Env e = new Env();
        e.install("toast, read_messages", "function on_message(m) tg.toast('got ' .. m.text) end");
        check("handler runs and uses the API", e.engine.dispatchMessage(msg("hello")) == 1 && e.host.has("toast:got hello"));
        check("outgoing messages are never reported",
                e.engine.dispatchMessage(new PgHost.Message(2, 42, 7, 1, PluginEvent.ChatKind.GROUP, true, "mine")) == 0 && e.host.count("toast") == 1);
        check("syntax error is reported at install", installRejects("toast", "function on_message(", "Lua error"));
        check("plugin that loops forever at load is rejected", installRejects("toast", "while true do end", "failed to start"));

        e = new Env();
        e.install("toast", "function on_message(m) tg.toast(m.text == nil and 'no text' or 'text') end");
        e.engine.dispatchMessage(msg("secret"));
        check("message text is hidden without read_messages", e.host.has("toast:no text"));

        e = new Env();
        e.install("read_messages", "function on_message(m) tg.log(tostring(#tg.get_messages(m.dialog_id, 5))) end");
        e.engine.dispatchMessage(msg("x"));
        check("get_messages works with permission", e.host.has("log:1"));

        System.out.println("Permissions:");
        e = new Env();
        e.install(pg("toast", "function on_message(m) local ok, err = pcall(tg.send_message, 42, 'spam') tg.log(tostring(ok) .. ' ' .. tostring(err)) end"), EnumSet.allOf(Permission.class));
        e.engine.dispatchMessage(msg("x"));
        check("undeclared permission cannot be used even if the user 'grants' it", e.host.count("send:") == 0 && e.host.log.toString().contains("permission not granted"));

        e = new Env();
        e.install(pg("toast, send_messages", "function on_message(m) pcall(tg.send_message, 42, 'hi') tg.toast('x') end"), EnumSet.of(Permission.TOAST));
        e.engine.dispatchMessage(msg("x"));
        check("permission the user did not grant is refused", e.host.count("send:") == 0 && e.host.count("toast") == 1);

        System.out.println("Sandbox escapes:");
        e = new Env();
        e.install("toast", "local bad = {}\n"
                + "for _, n in ipairs{'io','os','debug','require','package','load','loadstring','dofile','loadfile','collectgarbage','coroutine','luajava','java','jit','module','newproxy'} do\n"
                + "  if _G[n] ~= nil then bad[#bad + 1] = n end\n"
                + "end\n"
                + "if string.dump ~= nil then bad[#bad + 1] = 'string.dump' end\n"
                + "tg.log('bad:' .. table.concat(bad, ','))");
        check("dangerous globals do not exist", e.host.has("log:bad:"));

        e = new Env();
        e.install("toast", "tg.log('a:' .. tostring(pcall(function() getmetatable('').__index.evil = 1 end)))\n"
                + "tg.log('b:' .. tostring(pcall(function() string.rep = nil end)))\n"
                + "tg.log('c:' .. tostring(pcall(function() rawset(string, 'evil', 1) end)))\n"
                + "tg.log('d:' .. tostring(pcall(function() setmetatable('abc', {}) end)))\n"
                + "tg.log('e:' .. tostring(pcall(function() getmetatable('').__index = {} end)))\n"
                + "tg.log('f:' .. tostring(pcall(function() table.insert(string, 'x') end)))");
        check("shared string library cannot be changed (poisoning)",
                e.host.has("log:a:false") && e.host.has("log:b:false") && e.host.has("log:c:false") && e.host.has("log:d:false") && e.host.has("log:e:false") && e.host.has("log:f:false"));
        Env e2 = new Env();
        e2.install("toast", "tg.log('other:' .. tostring(('x'):rep(2) == 'xx' and string.evil == nil))");
        check("another plugin sees an untouched string library", e2.host.has("log:other:true"));

        e = new Env();
        e.install("toast", "function on_message(m) x = (x or 0) + 1 tg.log('x=' .. x) end");
        PgManifest second = PgManifest.parse("--@id second.pg\n--@name S\n--@version 1\n--@permissions toast\nfunction on_message(m) tg.log('second sees x=' .. tostring(x)) end");
        e.engine.install(second, EnumSet.allOf(Permission.class), true);
        e.engine.dispatchMessage(msg("a"));
        check("plugins do not share globals", e.host.has("log:x=1") && e.host.has("log:second sees x=nil"));

        System.out.println("Resource attacks:");
        long t0 = System.nanoTime();
        e = new Env();
        e.install("toast", "function on_message(m) while true do end end");
        e.engine.dispatchMessage(msg("go"));
        check("infinite loop is stopped quickly", (System.nanoTime() - t0) / 1_000_000 < 1500);
        check("limit hit is a strike, not yet disabled", e.engine.isEnabled("test.pg"));
        e.engine.dispatchMessage(msg("go"));
        e.engine.dispatchMessage(msg("go"));
        check("repeated limit hits switch the plugin off", !e.engine.isEnabled("test.pg") && e.disabled.contains("test.pg") && e.engine.getDisabledReason("test.pg") != null);
        check("a switched-off plugin does nothing", e.engine.dispatchMessage(msg("go")) == 0);
        e.engine.setEnabled("test.pg", true);
        check("the user can switch it on again", e.engine.isEnabled("test.pg"));

        e = new Env();
        e.install("toast", "function on_message(m) local ok = pcall(function() while true do end end) tg.toast('escaped pcall') end");
        e.engine.dispatchMessage(msg("go"));
        check("pcall cannot swallow the limit", !e.host.has("toast:escaped pcall"));

        e = new Env();
        e.install("toast", "function on_message(m) local function f() return f() end return f() end");
        e.engine.dispatchMessage(msg("go"));
        check("endless tail recursion is stopped", e.engine.getLastError("test.pg") != null && e.engine.getLastError("test.pg").contains("pg-limit"));

        e = new Env();
        e.install("toast", "function on_message(m) local function f(n) return 1 + f(n + 1) end return f(1) end");
        e.engine.dispatchMessage(msg("go"));
        check("endless deep recursion (stack overflow) is reported", e.engine.getLastError("test.pg") != null);
        check("engine still works after stack overflow", e.engine.dispatchMessage(msg("go")) == 1);

        e = new Env();
        e.install("toast", "function on_message(m) local s = 'x' while true do s = s .. s end end");
        e.engine.dispatchMessage(msg("go"));
        check("string doubling stops at the string limit (no memory blow-up)", e.engine.getLastError("test.pg") != null && e.engine.getLastError("test.pg").contains("string too long"));

        e = new Env();
        e.install("toast", "function on_message(m) local s = string.rep('x', 1000000000) end");
        e.engine.dispatchMessage(msg("go"));
        check("string.rep(1e9) is refused", e.engine.getLastError("test.pg").contains("string too long"));

        e = new Env();
        e.install("toast", "function on_message(m) local s = ('x'):rep(16000) local t = s:gsub('x', s) end");
        e.engine.dispatchMessage(msg("go"));
        check("gsub with huge input is refused", e.engine.getLastError("test.pg") != null);

        e = new Env();
        e.install("toast", "function on_message(m) return ('a'):rep(5000):find('a-a-a-a-b') end");
        e.engine.dispatchMessage(msg("go"));
        check("pattern matching on huge strings is refused (no ReDoS)", e.engine.getLastError("test.pg").contains("too long"));

        e = new Env();
        e.install("toast", "function on_message(m) return table.unpack({}, 1, 100000000) end");
        e.engine.dispatchMessage(msg("go"));
        check("table.unpack(1e8) is refused", e.engine.getLastError("test.pg").contains("too many values"));

        e = new Env();
        e.install("toast", "function on_message(m) local t = {} for i = 1, 1000000000 do t[i] = i end end");
        e.engine.dispatchMessage(msg("go"));
        check("giant table loop hits the instruction limit first", e.engine.getLastError("test.pg").contains("pg-limit"));

        System.out.println("Recursion through the client:");
        e = new Env();
        e.host.reenter = true;
        e.install("toast, read_messages", "function on_message(m) tg.toast('handled ' .. m.text) end");
        int ran = e.engine.dispatchMessage(msg("sale"));
        check("event raised by the client inside a plugin action is dropped", ran == 1 && e.host.count("toast") == 1 && e.engine.getDroppedReentrantEvents() == 1);
        check("engine works normally afterwards", e.engine.dispatchMessage(msg("sale")) == 1);

        e = new Env();
        e.install("send_messages", "function on_message(m) tg.send_message(m.dialog_id, 'reply') end");
        e.engine.dispatchMessage(msg("hi"));
        check("a plugin's own message is outgoing and is never reported back", e.host.count("send") == 1
                && e.engine.dispatchMessage(new PgHost.Message(3, 42, 1, 1, PluginEvent.ChatKind.GROUP, true, "reply")) == 0);

        System.out.println("Rate limits:");
        e = new Env();
        e.install("send_messages", "function on_message(m) for i = 1, 15 do local ok = pcall(tg.send_message, 42, 'm' .. i) end end");
        e.engine.dispatchMessage(msg("go"));
        check("at most 10 messages per minute", e.host.count("send:") == 10);
        e = new Env();
        e.install("toast", "function on_message(m) tg.toast('x') end");
        for (int i = 0; i < 40; i++) {
            e.engine.dispatchMessage(msg("go"));
        }
        check("toast flood is capped and the plugin is switched off", e.host.count("toast") == 20 && !e.engine.isEnabled("test.pg"));
        e = new Env();
        e.install("toast", "function on_message(m) tg.toast('x') end");
        int shown = 0;
        for (int i = 0; i < 200; i++) {
            fakeNow += 5000; // 12 per minute
            e.engine.dispatchMessage(msg("go"));
        }
        check("normal usage (12/min) is never limited", e.host.count("toast") == 200 && e.engine.isEnabled("test.pg"));

        System.out.println("Storage:");
        e = new Env();
        e.install("toast", "tg.storage_set('count', '5') tg.log('v:' .. tg.storage_get('count'))\n"
                + "tg.log('long:' .. tostring(pcall(tg.storage_set, 'k', ('x'):rep(2000))))\n"
                + "tg.log('badkey:' .. tostring(pcall(tg.storage_set, '../etc', 'x')))\n"
                + "local n = 0 for i = 1, 40 do if pcall(tg.storage_set, 'k' .. i, 'v') then n = n + 1 end end tg.log('keys:' .. n)");
        check("storage set/get works", e.host.has("log:v:5"));
        check("oversized value and bad key are refused", e.host.has("log:long:false") && e.host.has("log:badkey:false"));
        check("storage is limited to 32 keys", e.host.has("log:keys:31")); // 'count' already uses one
        check("storage changes are reported for saving", e.host.count("saved:") > 0);
        check("storage can be read back by the app", e.engine.getStorage("test.pg").get("count").equals("5"));

        System.out.println("Closed API (no session / Stars / NFT / payments):");
        e = new Env();
        e.install("toast", "local keys = {} for k in pairs(tg) do keys[#keys + 1] = k end table.sort(keys) tg.log('api:' .. table.concat(keys, ','))");
        String api = e.host.log.get(0);
        String[] forbidden = {"session", "auth", "login", "password", "2fa", "phone", "star", "gift", "nft", "pay", "invoice", "premium",
                "wallet", "token", "passcode", "secret", "account", "terminate", "logout", "device", "ton"};
        boolean clean = true;
        for (String f : forbidden) {
            if (api.contains(f)) {
                clean = false;
                System.out.println("       forbidden word in tg API: " + f);
            }
        }
        check("tg table has no forbidden function (" + api.substring(4) + ")", clean);
        clean = true;
        for (Method m : PgHost.class.getMethods()) {
            for (String f : forbidden) {
                if (m.getName().toLowerCase().contains(f)) {
                    clean = false;
                    System.out.println("       forbidden word in PgHost method: " + m.getName());
                }
            }
        }
        check("PgHost interface has no forbidden method", clean);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
