package org.telegram.messenger.timegram.plugins;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Host tests for the plugin engine (no Android, no JUnit needed). Run with tools/plugin-tests/run.sh.
 * Includes "attack" tests: recursion, flooding, oversized files, hostile JSON, permission bypass.
 */
public class PluginEngineTests {

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

    static boolean rejects(String json, String mustContain) {
        try {
            PluginParser.parse(json);
            return false;
        } catch (PluginException e) {
            return mustContain == null || e.getMessage().contains(mustContain);
        }
    }

    static String plugin(String permissions, String rules) {
        return "{\"schema\":1,\"id\":\"test.plugin\",\"name\":\"T\",\"version\":\"1\",\"permissions\":[" + permissions + "],\"rules\":[" + rules + "]}";
    }

    static final String MUTE_RULE = "{\"on\":\"message_received\",\"when\":[{\"type\":\"text_contains\",\"value\":\"sale\"}],\"do\":[{\"type\":\"mute_chat\",\"minutes\":60}]}";

    static class FakeHost implements PluginHost {
        final List<String> log = new ArrayList<>();
        PluginEngine engine; // set to make the host call back into the engine (a recursion attempt)
        boolean reenter;
        boolean throwing;

        public void showToast(String text) {
            log.add("toast:" + text);
            maybeReenter();
        }

        public void vibrate(int ms) {
            log.add("vibrate:" + ms);
            maybeReenter();
        }

        public void muteChat(long dialogId, int minutes) {
            log.add("mute:" + dialogId + ":" + minutes);
            maybeReenter();
        }

        void maybeReenter() {
            if (throwing) {
                throw new RuntimeException("host failure");
            }
            if (reenter && engine != null) {
                // like a mute that makes the client report "something changed" immediately
                engine.dispatch(PluginEvent.messageReceived(1, PluginEvent.ChatKind.PRIVATE, 5, "sale"));
            }
        }
    }

    static long fakeNow;

    static PluginEngine newEngine(FakeHost host) {
        fakeNow = 1_000_000;
        PluginEngine e = new PluginEngine(host, () -> fakeNow);
        host.engine = e;
        return e;
    }

    static PluginEvent msg(String text) {
        return PluginEvent.messageReceived(42, PluginEvent.ChatKind.GROUP, 7, text);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Parser:");
        Plugin ok = PluginParser.parse(plugin("\"read_messages\",\"mute_chats\"", MUTE_RULE));
        check("valid plugin parses", ok.id.equals("test.plugin") && ok.rules.size() == 1);
        check("empty file rejected", rejects("", "empty"));
        check("not json rejected", rejects("hello", "Invalid JSON"));
        check("unknown top-level field rejected", rejects("{\"schema\":1,\"id\":\"abc\",\"name\":\"T\",\"version\":\"1\",\"rules\":[],\"evil\":1}", "unknown field"));
        check("no rules rejected", rejects(plugin("", ""), "at least one rule"));
        check("bad schema rejected", rejects(plugin("", MUTE_RULE).replace("\"schema\":1", "\"schema\":2"), "schema"));
        check("bad id rejected", rejects(plugin("", MUTE_RULE).replace("test.plugin", "../../etc"), "id"));
        check("action without permission rejected", rejects(plugin("\"read_messages\"", MUTE_RULE), "needs the \"mute_chats\""));
        check("text condition without read_messages rejected", rejects(plugin("\"mute_chats\"", MUTE_RULE), "read_messages"));
        check("{text} without read_messages rejected",
                rejects(plugin("\"toast\"", "{\"on\":\"message_received\",\"do\":[{\"type\":\"toast\",\"text\":\"got {text}\"}]}"), "read_messages"));
        check("unknown permission rejected", rejects(plugin("\"root\"", MUTE_RULE), "Unknown permission"));
        check("unknown action rejected (no 'run', 'http', 'send')",
                rejects(plugin("\"toast\"", "{\"on\":\"app_opened\",\"do\":[{\"type\":\"http\",\"url\":\"x\"}]}"), "unknown action"));
        check("vibrate over the limit rejected",
                rejects(plugin("\"vibrate\"", "{\"on\":\"app_opened\",\"do\":[{\"type\":\"vibrate\",\"ms\":5000}]}"), "ms"));
        check("too many rules rejected", rejects(plugin("\"vibrate\"", repeat("{\"on\":\"app_opened\",\"do\":[{\"type\":\"vibrate\",\"ms\":10}]},", 31) + "{\"on\":\"app_opened\",\"do\":[{\"type\":\"vibrate\",\"ms\":10}]}"), "too many"));
        check("trailing comma rejected", rejects(plugin("", MUTE_RULE).replace("\"version\":\"1\"", "\"version\":\"1\","), "Invalid JSON"));
        check("comments rejected", rejects("/* x */" + plugin("", MUTE_RULE), "Invalid JSON"));
        check("single quotes rejected", rejects("{'schema':1}", "Invalid JSON"));
        check("duplicate key rejected", rejects("{\"schema\":1,\"schema\":1}", "duplicate"));
        check("deeply nested JSON rejected (no stack overflow)", rejects(repeat("[", 5000) + repeat("]", 5000), "too deep"));
        check("deeply nested object rejected", rejects(repeat("{\"a\":", 5000) + "1" + repeat("}", 5000), "too deep"));
        check("huge file rejected", rejects(plugin("", MUTE_RULE) + repeat(" ", PluginParser.MAX_FILE_CHARS), "too big"));
        check("exponent numbers rejected", rejects(plugin("\"vibrate\"", "{\"on\":\"app_opened\",\"do\":[{\"type\":\"vibrate\",\"ms\":1e2}]}"), "exponent"));
        check("rule cannot reference another plugin / include",
                rejects(plugin("", "{\"on\":\"app_opened\",\"do\":[{\"type\":\"toast\",\"text\":\"x\"}],\"include\":\"other\"}"), "unknown field"));

        System.out.println("Engine:");
        FakeHost host = new FakeHost();
        PluginEngine engine = newEngine(host);
        engine.install(ok, EnumSet.allOf(Permission.class), true);
        check("matching message runs the action", engine.dispatch(msg("big SALE today")) == 1 && host.log.contains("mute:42:60"));
        host.log.clear();
        check("non-matching message does nothing", engine.dispatch(msg("hello")) == 0 && host.log.isEmpty());
        check("other event type does nothing", engine.dispatch(PluginEvent.appOpened()) == 0);

        host = new FakeHost();
        engine = newEngine(host);
        engine.install(ok, EnumSet.of(Permission.READ_MESSAGES), true);
        check("action refused when permission was not granted", engine.dispatch(msg("sale")) == 0 && host.log.isEmpty());

        host = new FakeHost();
        engine = newEngine(host);
        engine.install(ok, EnumSet.of(Permission.MUTE_CHATS), true);
        check("text conditions are false without read_messages grant", engine.dispatch(msg("sale")) == 0);

        host = new FakeHost();
        engine = newEngine(host);
        engine.install(ok, EnumSet.allOf(Permission.class), false);
        check("disabled plugin does nothing", engine.dispatch(msg("sale")) == 0);

        System.out.println("Attacks:");
        // 1. recursion: the host calls dispatch from inside an action
        host = new FakeHost();
        host.reenter = true;
        engine = newEngine(host);
        engine.install(ok, EnumSet.allOf(Permission.class), true);
        int executed = engine.dispatch(msg("sale"));
        check("recursion is cut: 1 action, not an endless chain", executed == 1 && host.log.size() == 1);
        check("recursive events were counted as dropped", engine.getDroppedReentrantEvents() == 1);
        check("engine works normally after a recursion attempt", engine.dispatch(msg("sale")) == 1);

        // 2. two plugins that could 'feed' each other still cannot (events come only from the client)
        Plugin a = PluginParser.parse(plugin("\"toast\",\"read_messages\"", "{\"on\":\"message_received\",\"when\":[{\"type\":\"text_contains\",\"value\":\"ping\"}],\"do\":[{\"type\":\"toast\",\"text\":\"pong\"}]}").replace("test.plugin", "plugin.a"));
        Plugin b = PluginParser.parse(plugin("\"toast\",\"read_messages\"", "{\"on\":\"message_received\",\"when\":[{\"type\":\"text_contains\",\"value\":\"pong\"}],\"do\":[{\"type\":\"toast\",\"text\":\"ping\"}]}").replace("test.plugin", "plugin.b"));
        host = new FakeHost();
        host.reenter = false;
        engine = newEngine(host);
        engine.install(a, EnumSet.allOf(Permission.class), true);
        engine.install(b, EnumSet.allOf(Permission.class), true);
        engine.dispatch(msg("ping"));
        check("ping/pong plugins do not loop (toasts do not become messages)", host.log.size() == 1 && host.log.get(0).equals("toast:pong"));

        // 3. flooding: a plugin that fires on every message
        Plugin flood = PluginParser.parse(plugin("\"vibrate\"", "{\"on\":\"message_received\",\"do\":[{\"type\":\"vibrate\",\"ms\":10}]}").replace("test.plugin", "flood.one"));
        host = new FakeHost();
        engine = newEngine(host);
        final List<String> disabled = new ArrayList<>();
        engine.setListener((id, reason) -> disabled.add(id));
        engine.install(flood, EnumSet.allOf(Permission.class), true);
        int total = 0;
        for (int i = 0; i < 100; i++) {
            total += engine.dispatch(msg("x"));
        }
        check("flood is capped at " + PluginEngine.MAX_ACTIONS_PER_MINUTE + " actions per minute", total == PluginEngine.MAX_ACTIONS_PER_MINUTE);
        check("flooding plugin was switched off by the guard", !engine.isEnabled("flood.one") && disabled.contains("flood.one"));
        check("reason is recorded", engine.getDisabledReason("flood.one") != null);
        check("switched-off plugin stays off", engine.dispatch(msg("x")) == 0);
        engine.setEnabled("flood.one", true);
        fakeNow += 61_000;
        check("user can turn it back on (strikes reset)", engine.isEnabled("flood.one") && engine.dispatch(msg("x")) == 1);

        // 4. normal use over time is not punished
        host = new FakeHost();
        engine = newEngine(host);
        engine.install(flood, EnumSet.allOf(Permission.class), true);
        int ran = 0;
        for (int i = 0; i < 300; i++) {
            fakeNow += 5_000; // one message every 5 s = 12 per minute
            ran += engine.dispatch(msg("x"));
        }
        check("normal rate (12/min) is never limited", ran == 300 && engine.isEnabled("flood.one"));

        // 5. a failing host does not break the engine
        host = new FakeHost();
        host.throwing = true;
        engine = newEngine(host);
        engine.install(ok, EnumSet.allOf(Permission.class), true);
        boolean survived;
        try {
            engine.dispatch(msg("sale"));
            survived = true;
        } catch (Throwable t) {
            survived = false;
        }
        check("host exception is contained", survived);

        // 6. message text is shortened in toasts
        Plugin echo = PluginParser.parse(plugin("\"toast\",\"read_messages\"", "{\"on\":\"message_received\",\"do\":[{\"type\":\"toast\",\"text\":\"> {text}\"}]}").replace("test.plugin", "echo.text"));
        host = new FakeHost();
        engine = newEngine(host);
        engine.install(echo, EnumSet.allOf(Permission.class), true);
        engine.dispatch(msg(repeat("a", 5000)));
        check("long message text is truncated in toast", host.log.get(0).length() <= "toast:".length() + "> ".length() + PluginEngine.MAX_TOAST_TEXT_FROM_MESSAGE + 1);

        // 7. permissions can never be widened beyond what the plugin declared
        host = new FakeHost();
        engine = newEngine(host);
        Plugin quiet = PluginParser.parse(plugin("\"toast\"", "{\"on\":\"app_opened\",\"do\":[{\"type\":\"toast\",\"text\":\"hi\"}]}").replace("test.plugin", "quiet.one"));
        engine.install(quiet, EnumSet.allOf(Permission.class), true);
        check("granting extra permissions does nothing (declared set wins)", engine.dispatch(PluginEvent.appOpened()) == 1);

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
