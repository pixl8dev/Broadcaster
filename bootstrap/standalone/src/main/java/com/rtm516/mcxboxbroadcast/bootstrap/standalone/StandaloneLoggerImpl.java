package com.rtm516.mcxboxbroadcast.bootstrap.standalone;

import com.rtm516.mcxboxbroadcast.core.BuildData;
import com.rtm516.mcxboxbroadcast.core.Logger;
import net.minecrell.terminalconsole.SimpleTerminalConsole;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

import java.util.Arrays;

public class StandaloneLoggerImpl extends SimpleTerminalConsole implements Logger {
    private final org.slf4j.Logger logger;
    private final String prefixString;

    public StandaloneLoggerImpl(org.slf4j.Logger logger) {
        this(logger, "");
    }

    public StandaloneLoggerImpl(org.slf4j.Logger logger, String prefixString) {
        this.logger = logger;
        this.prefixString = prefixString;
    }

    @Override
    public void info(String message) {
        logger.info(prefix(message));
    }

    @Override
    public void warn(String message) {
        logger.warn(prefix(message));
    }

    @Override
    public void error(String message) {
        logger.error(prefix(message));
    }

    @Override
    public void error(String message, Throwable ex) {
        logger.error(prefix(message), ex);
    }

    @Override
    public void debug(String message) {
        logger.debug(prefix(message));
    }

    @Override
    public Logger prefixed(String prefixString) {
        return new StandaloneLoggerImpl(logger, prefixString);
    }

    private String prefix(String message) {
        if (prefixString.isEmpty()) {
            return message;
        } else {
            return "[" + prefixString + "] " + message;
        }
    }

    public void setDebug(boolean debug) {
        Configurator.setLevel(logger.getName(), debug ? Level.DEBUG : Level.INFO);
    }

    @Override
    protected boolean isRunning() {
        return true;
    }

    @Override
    protected void runCommand(String command) {
        String[] parts = command.split(" ");
        int offset = parts[0].equalsIgnoreCase("mcxboxbroadcast") ? 1 : 0;

        String commandNode = parts[offset].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, offset + 1, parts.length);

        try {
            switch (commandNode) {
                case "stop", "exit" -> System.exit(0);
                case "restart" -> StandaloneMain.restart();
                case "dumpsession" -> {
                    info("Dumping session responses to 'lastSessionResponse.json' and 'currentSessionResponse.json'");
                    StandaloneMain.sessionManager.dumpSession();
                }
                case "unfollowall" -> {
                    info("Queueing removal of all friends from the primary session...");
                    StandaloneMain.sessionManager.unfollowAllFriends();
                }
                case "unfriendinactive", "unfollowinactive" -> {
                    if (args.length < 1 || args.length > 2) {
                        warn("Usage: unfriendinactive <days> [--include-no-history]");
                        warn("Example: unfriendinactive 30 --include-no-history");
                        return;
                    }

                    boolean includeNoHistory = false;
                    if (args.length == 2) {
                        if ("--include-no-history".equalsIgnoreCase(args[1])) {
                            includeNoHistory = true;
                        } else {
                            warn("Unknown flag: " + args[1]);
                            warn("Supported flags: --include-no-history");
                            return;
                        }
                    }

                    StandaloneMain.sessionManager.unfollowInactiveFriends(args[0], includeNoHistory);
                }
                case "notifyfriends" -> {
                    info("Queueing invite notifications to followed friends...");
                    StandaloneMain.sessionManager.notifyFriends();
                }
                case "debugexpiry", "debuginactive" -> {
                    if (args.length > 2) {
                        warn("Usage: debugexpiry [period] [limit]");
                        warn("Example: debugexpiry 7d 25");
                        return;
                    }

                    String period = args.length >= 1 ? args[0] : null;
                    String limit = args.length >= 2 ? args[1] : null;
                    StandaloneMain.sessionManager.debugInactivityExpiry(period, limit);
                }
                case "accounts" -> {
                    if (args.length == 0) {
                        warn("Usage:");
                        warn("accounts list");
                        warn("accounts add/remove <sub-session-id>");
                        return;
                    }

                    switch (args[0].toLowerCase()) {
                        case "list" -> StandaloneMain.sessionManager.listSessions();
                        case "add" -> StandaloneMain.sessionManager.addSubSession(args[1]);
                        case "remove" -> StandaloneMain.sessionManager.removeSubSession(args[1]);
                        default -> warn("Unknown accounts command: " + args[0]);
                    }
                }
                case "version" -> info("MCXboxBroadcast Standalone " + BuildData.VERSION);
                case "help" -> {
                    info("Available commands:");
                    info("exit - Exit the application");
                    info("restart - Restart the application");
                    info("dumpsession - Dump the current session to json files");
                    info("unfollowall - Remove all friends from the primary session account");
                    info("unfriendinactive <days> [--include-no-history] - Remove followed friends inactive for the given days");
                    info("notifyfriends - Send invites to followed friends (batched 10 every 5 seconds)");
                    info("debugexpiry [period] [limit] - List players due to be removed for inactivity");
                    info("accounts list - List sub-accounts");
                    info("accounts add <sub-session-id> - Add a sub-account");
                    info("accounts remove <sub-session-id> - Remove a sub-account");
                    info("version - Display the version");
                }
                default -> warn("Unknown command: " + commandNode);
            }
        } catch (Exception e) {
            error("Failed to execute command", e);
        }
    }

    @Override
    protected void shutdown() {

    }
}
