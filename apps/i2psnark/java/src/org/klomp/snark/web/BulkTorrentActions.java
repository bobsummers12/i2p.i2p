package org.klomp.snark.web;

/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Stop-all-seeding and remove-all decisions for I2PSnark.
 *
 * Removal deletes only a torrent's .torrent metadata file. It never deletes
 * the downloaded file or directory, including partial data. The destructive
 * steps run only for POST, and only after the one-time confirmation token
 * matches. Cancel and a missing or wrong token change nothing.
 *
 * This class does not open tunnels or talk to peers. The servlet supplies
 * the stop and remove callbacks.
 *
 * @since 2.14.1 custom build
 */
public final class BulkTorrentActions {

    public static final String ACTION_STOP = "StopSeeding";
    public static final String ACTION_REMOVE = "RemoveAll";
    public static final String ACTION_CANCEL = "CancelBulk";

    public static final String MSG_BAD_METHOD =
        "Bad form method, POST required. Nothing was changed. Downloaded data was not deleted.";
    public static final String MSG_NONE_LOADED =
        "No torrents loaded. Nothing was changed. Downloaded data was not deleted.";
    public static final String MSG_NONE_SEEDING =
        "No running completed torrents. Incomplete downloads were left running. Downloaded files were not deleted.";
    public static final String MSG_NEED_STOP =
        "Stop {0} seeding torrents? Only running completed torrents are stopped. Incomplete downloads keep running. Downloaded files are not deleted.";
    public static final String MSG_NEED_REMOVE =
        "Remove all {0} torrents from the active list, including stopped torrents and magnets, and delete the .torrent metadata files? Downloaded files and partial data directories are kept and will not be deleted.";
    public static final String MSG_CANCELLED =
        "Cancelled. No torrents were changed. Downloaded data was not deleted.";
    public static final String MSG_STOPPED =
        "Stopped {0} seeding torrents. Incomplete downloads were left running. Downloaded files were not deleted.";
    public static final String MSG_REMOVED =
        "Removed {0} torrents from the active list. .torrent metadata files were deleted when present. Downloaded files and partial data directories were not deleted.";
    public static final String MSG_REMOVE_PARTIAL =
        "Removed {0} torrents from the active list. Some metadata could not be deleted. Downloaded files and partial data directories were not deleted.";
    public static final String MSG_REMOVE_NONE =
        "No torrents were removed. Downloaded files and partial data directories were not deleted.";

    public static final String REASON_NOT_FILE =
        "Refusing to delete metadata because it is not a .torrent file.";
    public static final String REASON_INSIDE_PAYLOAD =
        "Refusing to delete metadata because it is inside the downloaded data.";
    public static final String REASON_IS_PAYLOAD =
        "Refusing to delete metadata because it is the downloaded data.";
    public static final String REASON_NO_PERM =
        "No write permission to delete the .torrent metadata file.";
    public static final String REASON_DELETE_FAILED =
        "Could not delete the .torrent metadata file.";
    public static final String REASON_NOT_FOUND =
        "Torrent was not found.";
    public static final String REASON_CALLBACK =
        "The torrent could not be updated.";

    /** Leave the torrent on the list. Do not delete its data files. */
    public interface Stopper {
        void stopLeavingOnList(Entry entry) throws Exception;
    }

    /**
     * Drop the torrent from the active list and its saved status.
     * Must not delete downloaded or partial data files.
     */
    public interface Remover {
        void removeFromList(Entry entry) throws Exception;
    }

    /** Deletes one metadata file. Production uses {@link File#delete()}. */
    public interface FileDeleter {
        boolean delete(File file);
    }

    public static final class Entry {
        public final String id;
        public final String displayName;
        public final boolean stopped;
        public final boolean checking;
        public final boolean hasMetaInfo;
        public final boolean fetchOrMagnet;
        public final boolean storageComplete;
        /** .torrent file, or null for a magnet or fetch with no metadata file. */
        public final File metadata;
        /** Downloaded file or directory, or null if this torrent has no storage yet. */
        public final File payload;

        public Entry(String id, String displayName, boolean stopped, boolean checking,
                     boolean hasMetaInfo, boolean fetchOrMagnet, boolean storageComplete,
                     File metadata, File payload) {
            this.id = id;
            this.displayName = displayName != null ? displayName : "";
            this.stopped = stopped;
            this.checking = checking;
            this.hasMetaInfo = hasMetaInfo;
            this.fetchOrMagnet = fetchOrMagnet;
            this.storageComplete = storageComplete;
            this.metadata = metadata;
            this.payload = payload;
        }
    }

    public enum Kind { NO_OP, NEEDS_CONFIRM, APPLIED }

    public static final class Failure {
        public final String name;
        public final String reason;

        public Failure(String name, String reason) {
            this.name = name != null ? name : "";
            this.reason = reason != null ? reason : REASON_CALLBACK;
        }
    }

    public static final class Outcome {
        public final Kind kind;
        /** Passed to the translator. Contains "{0}" only when {@link #count} is non-negative. */
        public final String messageKey;
        public final int count;
        /** Null clears the pending confirmation when {@link #clearPending} is true. */
        public final String pendingToStore;
        public final boolean clearPending;
        public final List<Failure> failures;

        Outcome(Kind kind, String messageKey, int count, String pendingToStore,
                boolean clearPending, List<Failure> failures) {
            this.kind = kind;
            this.messageKey = messageKey;
            this.count = count;
            this.pendingToStore = pendingToStore;
            this.clearPending = clearPending;
            if (failures == null || failures.isEmpty())
                this.failures = Collections.emptyList();
            else
                this.failures = Collections.unmodifiableList(new ArrayList<Failure>(failures));
        }
    }

    public static final class Pending {
        public final String action;
        public final String token;

        Pending(String action, String token) {
            this.action = action;
            this.token = token;
        }
    }

    private BulkTorrentActions() {}

    /**
     * Running, not being rechecked, has metainfo, and the storage is complete.
     * Magnets, fetches, stopped torrents, and incomplete downloads are not seeding.
     */
    public static boolean isSeeding(Entry entry) {
        return entry != null
            && !entry.stopped
            && !entry.checking
            && entry.hasMetaInfo
            && !entry.fetchOrMagnet
            && entry.storageComplete;
    }

    public static int countSeeding(List<Entry> entries) {
        int n = 0;
        if (entries == null)
            return 0;
        for (int i = 0; i < entries.size(); i++) {
            if (isSeeding(entries.get(i)))
                n++;
        }
        return n;
    }

    public static Pending parsePending(String stored) {
        if (stored == null)
            return null;
        int nl = stored.indexOf('\n');
        if (nl <= 0 || nl >= stored.length() - 1)
            return null;
        String action = stored.substring(0, nl);
        String token = stored.substring(nl + 1);
        if (!ACTION_STOP.equals(action) && !ACTION_REMOVE.equals(action))
            return null;
        if (!isDigits(token))
            return null;
        return new Pending(action, token);
    }

    /**
     * @param method HTTP method; anything other than POST is a no-op
     * @param action StopSeeding, RemoveAll, or CancelBulk
     * @param storedPending session value, or null
     * @param confirmParam posted confirmation token, or null
     * @param newToken token to store when confirmation is required; digits only
     */
    public static Outcome dispatch(String method, String action, List<Entry> entries,
                                   String storedPending, String confirmParam, String newToken,
                                   Stopper stopper, Remover remover, FileDeleter deleter) {
        if (!"POST".equals(method))
            return new Outcome(Kind.NO_OP, MSG_BAD_METHOD, -1, null, false, null);
        if (ACTION_CANCEL.equals(action))
            return new Outcome(Kind.NO_OP, MSG_CANCELLED, -1, null, true, null);
        if (entries == null)
            entries = Collections.emptyList();
        if (ACTION_STOP.equals(action))
            return stopSeeding(entries, storedPending, confirmParam, newToken, stopper);
        if (ACTION_REMOVE.equals(action))
            return removeAll(entries, storedPending, confirmParam, newToken, remover, deleter);
        return new Outcome(Kind.NO_OP, MSG_BAD_METHOD, -1, null, false, null);
    }

    private static Outcome stopSeeding(List<Entry> entries, String storedPending, String confirmParam,
                                       String newToken, Stopper stopper) {
        int seeding = countSeeding(entries);
        if (seeding == 0)
            return new Outcome(Kind.NO_OP, MSG_NONE_SEEDING, -1, null, true, null);
        if (!confirmed(ACTION_STOP, storedPending, confirmParam))
            return needsConfirm(ACTION_STOP, seeding, newToken, MSG_NEED_STOP);
        List<Failure> failures = new ArrayList<Failure>();
        int stopped = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!isSeeding(entry))
                continue;
            try {
                if (stopper == null)
                    throw new IllegalStateException(REASON_CALLBACK);
                stopper.stopLeavingOnList(entry);
                stopped++;
            } catch (Exception e) {
                failures.add(new Failure(entry.displayName, reasonOf(e)));
            }
        }
        return new Outcome(Kind.APPLIED, MSG_STOPPED, stopped, null, true, failures);
    }

    private static Outcome removeAll(List<Entry> entries, String storedPending, String confirmParam,
                                     String newToken, Remover remover, FileDeleter deleter) {
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) != null)
                count++;
        }
        if (count == 0)
            return new Outcome(Kind.NO_OP, MSG_NONE_LOADED, -1, null, true, null);
        if (!confirmed(ACTION_REMOVE, storedPending, confirmParam))
            return needsConfirm(ACTION_REMOVE, count, newToken, MSG_NEED_REMOVE);
        List<Failure> failures = new ArrayList<Failure>();
        int unlisted = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry == null)
                continue;
            if (!entry.fetchOrMagnet && entry.metadata != null) {
                String unsafe = unsafeMetadata(entry.metadata, entry.payload);
                if (unsafe != null) {
                    failures.add(new Failure(entry.displayName, unsafe));
                    continue;
                }
            }
            try {
                if (remover == null)
                    throw new IllegalStateException(REASON_CALLBACK);
                remover.removeFromList(entry);
            } catch (Exception e) {
                failures.add(new Failure(entry.displayName, reasonOf(e)));
                continue;
            }
            unlisted++;
            if (!entry.fetchOrMagnet && entry.metadata != null && entry.metadata.exists()) {
                boolean deleted = false;
                try {
                    File canon = entry.metadata.getCanonicalFile();
                    if (deleter != null)
                        deleted = deleter.delete(canon);
                    if (!deleted || canon.exists())
                        deleted = false;
                } catch (IOException ioe) {
                    deleted = false;
                }
                if (!deleted) {
                    // Already off the list. Do not touch the payload to "clean up".
                    failures.add(new Failure(entry.displayName, REASON_DELETE_FAILED));
                }
            }
        }
        if (unlisted == 0)
            return new Outcome(Kind.APPLIED, MSG_REMOVE_NONE, -1, null, true, failures);
        String key = failures.isEmpty() ? MSG_REMOVED : MSG_REMOVE_PARTIAL;
        return new Outcome(Kind.APPLIED, key, unlisted, null, true, failures);
    }

    private static Outcome needsConfirm(String action, int count, String newToken, String messageKey) {
        if (!isDigits(newToken))
            return new Outcome(Kind.NEEDS_CONFIRM, messageKey, count, null, false, null);
        return new Outcome(Kind.NEEDS_CONFIRM, messageKey, count, action + "\n" + newToken, false, null);
    }

    private static boolean confirmed(String action, String storedPending, String confirmParam) {
        Pending pending = parsePending(storedPending);
        if (pending == null || confirmParam == null)
            return false;
        return action.equals(pending.action) && pending.token.equals(confirmParam);
    }

    /**
     * @return a translation key when the file must not be deleted, or null when it is safe
     *         (including when the metadata file is already gone)
     */
    static String unsafeMetadata(File metadata, File payload) {
        if (metadata == null)
            return null;
        try {
            if (!metadata.exists())
                return null;
            File canon = metadata.getCanonicalFile();
            if (!canon.isFile())
                return REASON_NOT_FILE;
            if (!canon.getName().toLowerCase(Locale.ROOT).endsWith(".torrent"))
                return REASON_NOT_FILE;
            if (payload != null) {
                File payloadCanon = payload.getCanonicalFile();
                if (canon.equals(payloadCanon))
                    return REASON_IS_PAYLOAD;
                if (isInside(payloadCanon, canon))
                    return REASON_INSIDE_PAYLOAD;
            }
            File parent = canon.getParentFile();
            if (parent == null || !parent.isDirectory() || !parent.canWrite())
                return REASON_NO_PERM;
            return null;
        } catch (IOException ioe) {
            return REASON_DELETE_FAILED;
        }
    }

    private static boolean isInside(File directory, File file) throws IOException {
        if (directory == null || file == null || !directory.isDirectory())
            return false;
        File dir = directory.getCanonicalFile();
        File parent = file.getCanonicalFile().getParentFile();
        while (parent != null) {
            if (parent.equals(dir))
                return true;
            parent = parent.getParentFile();
        }
        return false;
    }

    private static String reasonOf(Exception e) {
        String message = e.getMessage();
        if (REASON_NOT_FOUND.equals(message) || REASON_CALLBACK.equals(message))
            return message;
        if (message == null || message.length() == 0)
            return REASON_CALLBACK;
        return message;
    }

    private static boolean isDigits(String token) {
        if (token == null || token.length() < 1 || token.length() > 40)
            return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9')
                return false;
        }
        return true;
    }

    public static String toolbarButton(String action, String imgSrc, String title) {
        return "<button type=\"submit\" name=\"action\" value=\"" + escapeHtml(action) + "\""
             + " class=\"snarkBulkBtn\" title=\"" + escapeHtml(title) + "\""
             + " aria-label=\"" + escapeHtml(title) + "\""
             + " style=\"background:transparent;border:0;padding:0;margin:0 1px;cursor:pointer;vertical-align:middle;\">"
             + "<img src=\"" + escapeHtml(imgSrc) + "\" alt=\"\">"
             + "<span class=\"bulkStar\" aria-hidden=\"true\""
             + " style=\"color:#900;background:#fff;border:1px solid #000;font-weight:700;"
             + "font-size:12px;line-height:1;padding:0 2px;margin-left:1px;vertical-align:super;\">*</span>"
             + "</button>";
    }

    public static String confirmForm(String action, String token, String nonce, String explanation,
                                     String confirmLabel, String cancelLabel) {
        StringBuilder buf = new StringBuilder(512);
        buf.append("<form class=\"snarkBulkConfirm\" action=\"_post\" method=\"POST\" accept-charset=\"UTF-8\"");
        buf.append(" style=\"background:#fff;color:#111;border:2px solid #900;padding:8px 12px;");
        buf.append("margin:8px auto;max-width:740px;text-align:left;\">\n");
        buf.append("<p id=\"snarkBulkExplain\">").append(escapeHtml(explanation)).append("</p>\n");
        buf.append("<input type=\"hidden\" name=\"nonce\" value=\"").append(escapeHtml(nonce)).append("\">\n");
        buf.append("<input type=\"hidden\" name=\"confirm\" value=\"").append(escapeHtml(token)).append("\">\n");
        buf.append("<button type=\"submit\" name=\"action\" value=\"").append(escapeHtml(action)).append("\"");
        buf.append(" style=\"background:#fff;color:#111;border:1px solid #000;padding:4px 10px;margin:4px 8px 0 0;font-weight:bold;\">");
        buf.append(escapeHtml(confirmLabel)).append("</button>");
        buf.append("<button type=\"submit\" name=\"action\" value=\"").append(ACTION_CANCEL).append("\"");
        buf.append(" style=\"background:#fff;color:#111;border:1px solid #000;padding:4px 10px;margin:4px 8px 0 0;font-weight:bold;\">");
        buf.append(escapeHtml(cancelLabel)).append("</button>\n");
        buf.append("</form>\n");
        return buf.toString();
    }

    static String escapeHtml(String s) {
        if (s == null)
            return "";
        StringBuilder buf = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': buf.append("&amp;"); break;
                case '<': buf.append("&lt;"); break;
                case '>': buf.append("&gt;"); break;
                case '"': buf.append("&quot;"); break;
                case '\'': buf.append("&#39;"); break;
                default: buf.append(c); break;
            }
        }
        return buf.toString();
    }
}
