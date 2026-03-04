package com.rtm516.mcxboxbroadcast.core;

import com.google.gson.JsonParseException;
import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionRequest;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionResponse;
import com.rtm516.mcxboxbroadcast.core.models.session.FollowerResponse;
import com.rtm516.mcxboxbroadcast.core.notifications.NotificationManager;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import org.java_websocket.util.NamedThreadFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple manager to authenticate and create sessions on Xbox
 */
public class SessionManager extends SessionManagerCore {
    private final ScheduledExecutorService scheduledThreadPool;
    private final Map<String, SubSessionManager> subSessionManagers;
    private final AtomicBoolean notifyFriendsInProgress;

    private CoreConfig.FriendSyncConfig friendSyncConfig;
    private Runnable restartCallback;

    /**
     * Create an instance of SessionManager
     *
     * @param storageManager The storage manager to use for storing data
     * @param notificationManager The notification manager to use for sending messages
     * @param logger The logger to use for outputting messages
     */
    public SessionManager(StorageManager storageManager, NotificationManager notificationManager, Logger logger) {
        super(storageManager, notificationManager, logger.prefixed("Primary Session"));
        this.scheduledThreadPool = Executors.newScheduledThreadPool(5, new NamedThreadFactory("MCXboxBroadcast Thread"));
        this.subSessionManagers = new HashMap<>();
        this.notifyFriendsInProgress = new AtomicBoolean(false);
    }

    @Override
    public ScheduledExecutorService scheduledThread() {
        return scheduledThreadPool;
    }

    @Override
    public String getSessionId() {
        return sessionInfo.getSessionId();
    }

    /**
     * Get the current session information
     *
     * @return The current session information
     */
    public ExpandedSessionInfo sessionInfo() {
        return sessionInfo;
    }

    /**
     * Initialize the session manager with the given session information
     *
     * @param sessionInfo      The session information to use
     * @param friendSyncConfig The friend sync configuration to use
     * @throws SessionCreationException If the session failed to create either because it already exists or some other reason
     * @throws SessionUpdateException   If the session data couldn't be set due to some issue
     */
    public boolean init(SessionInfo sessionInfo, CoreConfig.FriendSyncConfig friendSyncConfig) throws SessionCreationException, SessionUpdateException {
        // Set the internal session information based on the session info
        this.sessionInfo = new ExpandedSessionInfo("", "", sessionInfo);

        super.init();

        // If we failed to initialize, don't continue with the rest of the setup
        if (!this.initialized) {
            return this.initialized;
        }

        // Set up the auto friend sync
        this.friendSyncConfig = friendSyncConfig;
        friendManager().init(this.friendSyncConfig);

        // Load sub-sessions from cache
        List<String> subSessions = new ArrayList<>();
        try {
            String subSessionsJson = storageManager().subSessions();
            if (!subSessionsJson.isBlank()) {
                subSessions = Arrays.asList(Constants.GSON.fromJson(subSessionsJson, String[].class));
            }
        } catch (IOException ignored) { }

        // Create the sub-sessions in a new thread so we don't block the main thread
        List<String> finalSubSessions = subSessions;
        scheduledThreadPool.execute(() -> {
            // Create the sub-session manager for each sub-session
            for (String subSession : finalSubSessions) {
                try {
                    SubSessionManager subSessionManager = new SubSessionManager(subSession, this, storageManager().subSession(subSession), notificationManager(), logger);
                    subSessionManager.init();
                    subSessionManager.friendManager().init(this.friendSyncConfig);
                    subSessionManagers.put(subSession, subSessionManager);
                } catch (SessionCreationException | SessionUpdateException e) {
                    logger.error("Failed to create sub-session " + subSession, e);
                    // TODO Retry creation after 30s or so
                }
            }
        });

        return this.initialized;
    }

    @Override
    protected boolean handleFriendship() {
        // Don't do anything as we are the main session
        return false;
    }

    /**
     * Update the current session with new information
     *
     * @param sessionInfo The information to update the session with
     * @throws SessionUpdateException If the update failed
     */
    public void updateSession(SessionInfo sessionInfo) throws SessionUpdateException {
        this.sessionInfo.updateSessionInfo(sessionInfo);
        updateSession();
    }

    @Override
    protected void updateSession() throws SessionUpdateException {
        // Make sure the websocket connection is still active
        checkConnection();

        String responseBody = super.updateSessionInternal(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId()), new CreateSessionRequest(this.sessionInfo));
        try {
            CreateSessionResponse sessionResponse = Constants.GSON.fromJson(responseBody, CreateSessionResponse.class);

            // Restart if we have 28/30 session members
            int players = sessionResponse.members().size();
            if (players >= 28) {
                logger.info("Restarting session due to " + players + "/30 players");
                restart();
            }
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Failed to parse session response: " + e.getMessage());
        }
    }

    /**
     * Stop the current session and close the websocket
     */
    public void shutdown() {
        // Shutdown all sub-sessions
        for (SubSessionManager subSessionManager : subSessionManagers.values()) {
            subSessionManager.shutdown();
        }

        // Shutdown self
        super.shutdown();
        scheduledThreadPool.shutdownNow();
    }

    /**
     * Dump the current and last session responses to json files
     */
    public void dumpSession() {
        try {
            storageManager().lastSessionResponse(lastSessionResponse);
        } catch (IOException e) {
            logger.error("Error dumping last session: " + e.getMessage());
        }

        HttpRequest createSessionRequest = HttpRequest.newBuilder()
                .uri(URI.create(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId())))
                .header("Content-Type", "application/json")
                .header("Authorization", getTokenHeader())
                .header("x-xbl-contract-version", "107")
                .GET()
                .build();

        try {
            HttpResponse<String> createSessionResponse = httpClient.send(createSessionRequest, HttpResponse.BodyHandlers.ofString());

            storageManager().currentSessionResponse(createSessionResponse.body());
        } catch (IOException | InterruptedException e) {
            logger.error("Error dumping current session: " + e.getMessage());
        }
    }

    /**
     * Create a sub-session for the given ID
     *
     * @param id The ID of the sub-session to create
     */
    public void addSubSession(String id) {
        // Make sure we don't already have that ID
        if (subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session already exists with that ID");
            return;
        }

        // Create the sub-session manager
        try {
            SubSessionManager subSessionManager = new SubSessionManager(id, this, storageManager().subSession(id), notificationManager(), logger);
            subSessionManager.init();
            subSessionManager.friendManager().init(friendSyncConfig);
            subSessionManagers.put(id, subSessionManager);
        } catch (SessionCreationException | SessionUpdateException e) {
            coreLogger.error("Failed to create sub-session", e);
            return;
        }

        // Update the list of sub-sessions
        try {
            storageManager().subSessions(Constants.GSON.toJson(subSessionManagers.keySet()));
        } catch (JsonParseException | IOException e) {
            coreLogger.error("Failed to update sub-session list", e);
        }
    }

    /**
     * Remove a sub-session for the given ID
     *
     * @param id The ID of the sub-session to remove
     */
    public void removeSubSession(String id) {
        // Make sure we have that ID
        if (!subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session does not exist with that ID");
            return;
        }

        // Remove the sub-session manager
        subSessionManagers.get(id).shutdown();
        subSessionManagers.remove(id);

        // Delete the sub-session cache file
        try {
            storageManager().subSession(id).cleanup();
        } catch (IOException e) {
            coreLogger.error("Failed to delete sub-session cache file", e);
        }

        // Update the list of sub-sessions
        try {
            storageManager().subSessions(Constants.GSON.toJson(subSessionManagers.keySet()));
        } catch (JsonParseException | IOException e) {
            coreLogger.error("Failed to update sub-session list", e);
        }

        coreLogger.info("Removed sub-session with ID " + id);
    }

    /**
     * List all sessions and their information
     */
    public void listSessions() {
        List<String> messages = new ArrayList<>();
        coreLogger.info("Loading status of sessions...");

        messages.add("Primary Session:");
        messages.add(" - Gamertag: " + getGamertag());
        messages.add("   Following: " + socialSummary().targetFollowingCount() + "/" + Constants.MAX_FRIENDS);

        if (!subSessionManagers.isEmpty()) {
            messages.add("Sub-sessions: (" + subSessionManagers.size() + ")");
            for (Map.Entry<String, SubSessionManager> subSession : subSessionManagers.entrySet()) {
                messages.add(" - ID: " + subSession.getKey());
                messages.add("   Gamertag: " + subSession.getValue().getGamertag());
                messages.add("   Following: " + subSession.getValue().socialSummary().targetFollowingCount() + "/" + Constants.MAX_FRIENDS);
            }
        } else {
            messages.add("No sub-sessions");
        }

        for (String message : messages) {
            coreLogger.info(message);
        }
    }

    /**
     * Queue removing all friends from the primary session account.
     */
    public void unfollowAllFriends() {
        scheduledThreadPool.execute(() -> {
            List<FollowerResponse.Person> friends;
            try {
                friends = friendManager().get();
            } catch (Exception e) {
                coreLogger.error("Failed to load friend list for unfollowall", e);
                return;
            }

            int queued = 0;
            for (FollowerResponse.Person friend : friends) {
                if (!friend.isFollowedByCaller) {
                    continue;
                }

                friendManager().remove(friend.xuid, friend.gamertag);
                queued++;
            }

            coreLogger.info("Queued " + queued + " friends for removal from primary session.");
            if (queued > 0) {
                coreLogger.info("Removal is processed in batches and may take time due to Xbox rate limits.");
            }
        });
    }

    /**
     * Send invites to all followed friends in controlled batches.
     * Batching is fixed to 10 invites every 5 seconds to avoid aggressive bursts.
     */
    public void notifyFriends() {
        if (!notifyFriendsInProgress.compareAndSet(false, true)) {
            coreLogger.warn("A notifyfriends run is already in progress.");
            return;
        }

        scheduledThreadPool.execute(() -> {
            List<FollowerResponse.Person> friends;
            try {
                friends = friendManager().get();
            } catch (Exception e) {
                coreLogger.error("Failed to load friend list for notifyfriends", e);
                notifyFriendsInProgress.set(false);
                return;
            }

            List<FollowerResponse.Person> recipients = friends.stream()
                .filter(friend -> friend != null && friend.xuid != null && friend.isFollowedByCaller)
                .toList();

            if (recipients.isEmpty()) {
                coreLogger.info("No followed friends found to notify.");
                notifyFriendsInProgress.set(false);
                return;
            }

            final int batchSize = 10;
            final int batchDelaySeconds = 5;
            int total = recipients.size();
            int totalBatches = (int) Math.ceil((double) total / batchSize);

            coreLogger.info("Queueing invites to " + total + " friend(s) in " + totalBatches + " batch(es) of " + batchSize + " every " + batchDelaySeconds + "s.");

            for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                int fromIndex = batchIndex * batchSize;
                int toIndex = Math.min(fromIndex + batchSize, total);
                List<FollowerResponse.Person> batch = recipients.subList(fromIndex, toIndex);
                int batchNumber = batchIndex + 1;
                boolean lastBatch = batchNumber == totalBatches;

                scheduledThreadPool.schedule(() -> {
                    try {
                        coreLogger.info("Sending invite batch " + batchNumber + "/" + totalBatches + " (" + batch.size() + " friend(s))");
                        for (FollowerResponse.Person friend : batch) {
                            String gamertag = friend.gamertag != null && !friend.gamertag.isBlank() ? friend.gamertag : friend.displayName;
                            if (gamertag == null || gamertag.isBlank()) {
                                gamertag = "Unknown";
                            }

                            friendManager().sendInvite(friend.xuid, true);
                            coreLogger.info("Sent invite to " + gamertag + " (" + friend.xuid + ")");
                        }
                    } finally {
                        if (lastBatch) {
                            notifyFriendsInProgress.set(false);
                            coreLogger.info("notifyfriends completed.");
                        }
                    }
                }, (long) batchIndex * batchDelaySeconds, TimeUnit.SECONDS);
            }
        });
    }

    /**
     * Debug inactivity expiry behaviour by listing players that are already expired
     * or due to expire in a target window.
     *
     * @param periodArg Window to include, e.g. 12h, 7d (default unit is days)
     * @param limitArg Max number of results to print
     */
    public void debugInactivityExpiry(String periodArg, String limitArg) {
        if (friendSyncConfig == null) {
            coreLogger.warn("Friend sync is not initialized yet.");
            return;
        }

        Long periodSeconds = parsePeriodToSeconds(periodArg);
        if (periodArg != null && !periodArg.isBlank() && periodSeconds == null) {
            coreLogger.warn("Invalid period: " + periodArg + ". Use <number>[s|m|h|d], e.g. 12h or 7d.");
            return;
        }

        int limit = 25;
        if (limitArg != null && !limitArg.isBlank()) {
            try {
                limit = Integer.parseInt(limitArg);
                if (limit <= 0) {
                    coreLogger.warn("Limit must be greater than 0.");
                    return;
                }
            } catch (NumberFormatException e) {
                coreLogger.warn("Invalid limit: " + limitArg + ". Use a positive whole number.");
                return;
            }
        }

        if (periodSeconds == null) {
            periodSeconds = TimeUnit.DAYS.toSeconds(Math.max(1, friendSyncConfig.expiry().days()));
        }
        long resolvedPeriodSeconds = periodSeconds;
        int resolvedLimit = limit;

        scheduledThreadPool.execute(() -> debugInactivityExpiryInternal(resolvedPeriodSeconds, resolvedLimit));
    }

    private void debugInactivityExpiryInternal(long periodSeconds, int limit) {
        CoreConfig.FriendSyncConfig.ExpiryConfig expiryConfig = friendSyncConfig.expiry();
        Instant now = Instant.now();
        Instant windowEnd = now.plusSeconds(periodSeconds);

        coreLogger.info("Inactivity debug:");
        coreLogger.info(" - Expiry enabled: " + expiryConfig.enabled());
        coreLogger.info(" - Expiry threshold: " + expiryConfig.days() + " day(s)");
        coreLogger.info(" - Expiry check interval: " + expiryConfig.check() + "s");
        coreLogger.info(" - Window: " + formatDuration(periodSeconds) + " (until " + windowEnd + ")");

        List<FriendManager.InactivityExpiryCandidate> candidates;
        try {
            candidates = friendManager().inactivityExpiryCandidates(expiryConfig.days());
        } catch (IOException e) {
            coreLogger.error("Failed to load inactivity debug data", e);
            return;
        }

        if (candidates.isEmpty()) {
            coreLogger.info("No player history entries found.");
            return;
        }

        long expiredNow = 0;
        long expiringInWindow = 0;
        for (FriendManager.InactivityExpiryCandidate candidate : candidates) {
            if (candidate.expired()) {
                expiredNow++;
            } else if (!candidate.expiresAt().isAfter(windowEnd)) {
                expiringInWindow++;
            }
        }

        coreLogger.info("Tracked players: " + candidates.size());
        coreLogger.info("Already expired: " + expiredNow);
        coreLogger.info("Expiring within window: " + expiringInWindow);

        int shown = 0;
        for (FriendManager.InactivityExpiryCandidate candidate : candidates) {
            if (!candidate.expired() && candidate.expiresAt().isAfter(windowEnd)) {
                continue;
            }

            String status;
            if (candidate.expired()) {
                status = "EXPIRED " + formatDuration(-candidate.secondsUntilExpiry()) + " AGO";
            } else {
                status = "IN " + formatDuration(candidate.secondsUntilExpiry());
            }

            coreLogger.info(
                " - [" + status + "] " + candidate.gamertag() + " (" + candidate.xuid() + "), " +
                    "followedByCaller=" + candidate.followedByCaller() + ", " +
                    "lastSeen=" + candidate.lastSeen() + ", expires=" + candidate.expiresAt()
            );

            shown++;
            if (shown >= limit) {
                break;
            }
        }

        if (shown == 0) {
            coreLogger.info("No players are due within the selected window.");
            FriendManager.InactivityExpiryCandidate next = candidates.stream()
                .filter(candidate -> !candidate.expired())
                .findFirst()
                .orElse(null);
            if (next != null) {
                coreLogger.info(
                    "Next expiry outside the window: " + next.gamertag() + " (" + next.xuid() + ") in " +
                        formatDuration(next.secondsUntilExpiry()) + " at " + next.expiresAt()
                );
            }
        }
    }

    private static Long parsePeriodToSeconds(String period) {
        if (period == null || period.isBlank()) {
            return null;
        }

        String value = period.trim().toLowerCase(Locale.ROOT);
        long multiplier = TimeUnit.DAYS.toSeconds(1);
        char suffix = value.charAt(value.length() - 1);
        if (!Character.isDigit(suffix)) {
            value = value.substring(0, value.length() - 1);
            switch (suffix) {
                case 's' -> multiplier = 1;
                case 'm' -> multiplier = TimeUnit.MINUTES.toSeconds(1);
                case 'h' -> multiplier = TimeUnit.HOURS.toSeconds(1);
                case 'd' -> multiplier = TimeUnit.DAYS.toSeconds(1);
                default -> {
                    return null;
                }
            }
        }

        if (value.isBlank()) {
            return null;
        }

        long amount;
        try {
            amount = Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }

        if (amount <= 0) {
            return null;
        }

        try {
            return Math.multiplyExact(amount, multiplier);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0s";
        }

        long days = totalSeconds / TimeUnit.DAYS.toSeconds(1);
        totalSeconds %= TimeUnit.DAYS.toSeconds(1);
        long hours = totalSeconds / TimeUnit.HOURS.toSeconds(1);
        totalSeconds %= TimeUnit.HOURS.toSeconds(1);
        long minutes = totalSeconds / TimeUnit.MINUTES.toSeconds(1);
        long seconds = totalSeconds % TimeUnit.MINUTES.toSeconds(1);

        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        if (seconds > 0 || parts.isEmpty()) parts.add(seconds + "s");
        return String.join(" ", parts);
    }

    /**
     * Set the callback to run when the session manager needs to be restarted
     *
     * @param restart The callback to run
     */
    public void restartCallback(Runnable restart) {
        this.restartCallback = restart;
    }

    /**
     * Restart the session manager
     */
    public void restart() {
        if (restartCallback != null) {
            restartCallback.run();
        } else {
            logger.error("No restart callback set");
        }
    }
}
