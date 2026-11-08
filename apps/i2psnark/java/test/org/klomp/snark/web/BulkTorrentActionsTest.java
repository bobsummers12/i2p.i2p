package org.klomp.snark.web;

/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Isolated checks for stop-all-seeding and remove-all.
 * No router, no sockets, and no torrent traffic.
 *
 * Run with JDK 21 (class files target the same release the tests are compiled for):
 *   javac --release 17 -d /tmp/i2psnark-bulk-test \
 *     apps/i2psnark/java/src/org/klomp/snark/web/BulkTorrentActions.java \
 *     apps/i2psnark/java/test/org/klomp/snark/web/BulkTorrentActionsTest.java
 *   java -cp /tmp/i2psnark-bulk-test org.klomp.snark.web.BulkTorrentActionsTest
 */
public class BulkTorrentActionsTest {

    private int passed;
    private int failed;
    private final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        BulkTorrentActionsTest test = new BulkTorrentActionsTest();
        test.runAll();
        if (test.failed > 0) {
            for (int i = 0; i < test.failures.size(); i++)
                System.err.println("FAIL " + test.failures.get(i));
            System.err.println(test.failed + " failed, " + test.passed + " passed");
            System.exit(1);
        }
        System.out.println("OK " + test.passed + " checks");
    }

    private void runAll() throws Exception {
        check("seeding selector ignores incomplete, stopped, magnet, fetch, and checking",
              seedingSelector());
        check("empty list is a no-op for stop and remove", emptyList());
        check("stop asks for confirmation and cancel changes nothing", stopConfirmAndCancel());
        check("stop applies only to running complete torrents and keeps every payload byte",
              stopAppliesOnlyToSeeding());
        check("remove asks, cancel is a no-op, and GET does not delete", removeConfirmCancelAndGet());
        check("confirmed remove deletes only stopped metadata and keeps every payload",
              removeKeepsPayload());
        check("stopped magnets are left in place and their partial data stays",
              magnetAndStopped());
        check("mixed running stalled downloading rechecking starting rows stay put",
              mixedOnlyStoppedRemoved());
        check("a torrent restarted since confirmation is skipped", restartedSinceConfirmation());
        check("unsafe metadata is reported and left in place", unsafeMetadataReported());
        check("a failed metadata delete is reported and does not touch the payload",
              deleteFailureReported());
        check("a callback failure is reported and later torrents still update", callbackFailure());
        check("a confirmation token for the other action does not apply", wrongActionToken());
        check("a metadata symlink to the payload is not deleted", symlinkDoesNotDeletePayload());
        check("toolbar and confirm markup are POST, labeled, and show a star", markup());
    }

    private boolean seedingSelector() {
        BulkTorrentActions.Entry seeding = entry("seed", "Seed", false, false, true, false, true, null, null);
        BulkTorrentActions.Entry downloading = entry("dl", "Down", false, false, true, false, false, null, null);
        BulkTorrentActions.Entry stopped = entry("st", "Stopped", true, true, true, false, true, null, null);
        BulkTorrentActions.Entry magnet = entry("mag", "Magnet", false, false, false, true, false, null, null);
        BulkTorrentActions.Entry fetch = entry("fetch", "Fetch", false, false, false, true, false, null, null);
        BulkTorrentActions.Entry checking = entry("chk", "Check", false, true, true, false, true, null, null);
        return BulkTorrentActions.isSeeding(seeding)
            && !BulkTorrentActions.isSeeding(downloading)
            && !BulkTorrentActions.isSeeding(stopped)
            && !BulkTorrentActions.isSeeding(magnet)
            && !BulkTorrentActions.isSeeding(fetch)
            && !BulkTorrentActions.isSeeding(checking)
            && BulkTorrentActions.countSeeding(Arrays.asList(seeding, downloading, stopped, magnet, fetch, checking)) == 1
            && !BulkTorrentActions.isSeeding(null);
    }

    private boolean emptyList() {
        Recorder recorder = new Recorder();
        BulkTorrentActions.Outcome stop = BulkTorrentActions.dispatch(
            "POST", BulkTorrentActions.ACTION_STOP, new ArrayList<BulkTorrentActions.Entry>(),
            null, null, "123", recorder, recorder, recorder);
        BulkTorrentActions.Outcome remove = BulkTorrentActions.dispatch(
            "POST", BulkTorrentActions.ACTION_REMOVE, null,
            "RemoveAll\n5", "5", "123", recorder, recorder, recorder);
        return stop.kind == BulkTorrentActions.Kind.NO_OP
            && BulkTorrentActions.MSG_NONE_SEEDING.equals(stop.messageKey)
            && stop.clearPending
            && remove.kind == BulkTorrentActions.Kind.NO_OP
            && BulkTorrentActions.MSG_NONE_LOADED.equals(remove.messageKey)
            && recorder.stopped.isEmpty()
            && recorder.removed.isEmpty()
            && recorder.deleted.isEmpty();
    }

    private boolean stopConfirmAndCancel() throws Exception {
        Fixture fix = Fixture.mixed();
        try {
            Recorder recorder = new Recorder();
            Map<String, byte[]> before = fix.snapshot();
            BulkTorrentActions.Outcome ask = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_STOP, fix.entries, null, null, "444",
                recorder, recorder, recorder);
            BulkTorrentActions.Pending pending = BulkTorrentActions.parsePending(ask.pendingToStore);
            BulkTorrentActions.Outcome cancel = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_CANCEL, fix.entries, ask.pendingToStore, "444", "1",
                recorder, recorder, recorder);
            boolean same = sameSnap(before, fix.snapshot());
            return ask.kind == BulkTorrentActions.Kind.NEEDS_CONFIRM
                && ask.count == 1
                && BulkTorrentActions.MSG_NEED_STOP.equals(ask.messageKey)
                && pending != null
                && BulkTorrentActions.ACTION_STOP.equals(pending.action)
                && "444".equals(pending.token)
                && !ask.clearPending
                && cancel.kind == BulkTorrentActions.Kind.NO_OP
                && BulkTorrentActions.MSG_CANCELLED.equals(cancel.messageKey)
                && cancel.clearPending
                && recorder.stopped.isEmpty()
                && recorder.removed.isEmpty()
                && same
                && fix.metadata("seed.torrent").exists();
        } finally {
            fix.delete();
        }
    }

    private boolean stopAppliesOnlyToSeeding() throws Exception {
        Fixture fix = Fixture.mixed();
        try {
            Recorder recorder = new Recorder();
            Map<String, byte[]> before = fix.snapshot();
            String pending = BulkTorrentActions.ACTION_STOP + "\n777";
            BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_STOP, fix.entries, pending, "777", "1",
                recorder, recorder, recorder);
            Map<String, byte[]> after = fix.snapshot();
            return applied.kind == BulkTorrentActions.Kind.APPLIED
                && applied.count == 1
                && BulkTorrentActions.MSG_STOPPED.equals(applied.messageKey)
                && applied.clearPending
                && recorder.stopped.equals(Arrays.asList("seed"))
                && recorder.removed.isEmpty()
                && recorder.deleted.isEmpty()
                && sameSnap(before, after)
                && fix.metadata("seed.torrent").exists()
                && fix.metadata("partial.torrent").exists();
        } finally {
            fix.delete();
        }
    }

    private boolean removeConfirmCancelAndGet() throws Exception {
        Fixture fix = Fixture.mixed();
        try {
            Recorder recorder = new Recorder();
            Map<String, byte[]> before = fix.snapshot();
            BulkTorrentActions.Outcome ask = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, fix.entries, null, null, "42",
                recorder, recorder, recorder);
            BulkTorrentActions.Outcome get = BulkTorrentActions.dispatch(
                "GET", BulkTorrentActions.ACTION_REMOVE, fix.entries, ask.pendingToStore, "42", "9",
                recorder, recorder, recorder);
            BulkTorrentActions.Outcome mismatch = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, fix.entries, ask.pendingToStore, "no", "8",
                recorder, recorder, recorder);
            BulkTorrentActions.Outcome cancel = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_CANCEL, fix.entries, ask.pendingToStore, "42", "7",
                recorder, recorder, recorder);
            return ask.kind == BulkTorrentActions.Kind.NEEDS_CONFIRM
                && ask.count == 1
                && ask.messageKey.startsWith("Remove stopped torrents:")
                && ask.messageKey.contains("kept and will not be deleted")
                && get.kind == BulkTorrentActions.Kind.NO_OP
                && BulkTorrentActions.MSG_BAD_METHOD.equals(get.messageKey)
                && !get.clearPending
                && mismatch.kind == BulkTorrentActions.Kind.NEEDS_CONFIRM
                && cancel.messageKey.equals(BulkTorrentActions.MSG_CANCELLED)
                && recorder.removed.isEmpty()
                && recorder.deleted.isEmpty()
                && sameSnap(before, fix.snapshot());
        } finally {
            fix.delete();
        }
    }

    private boolean removeKeepsPayload() throws Exception {
        Fixture fix = Fixture.mixed();
        try {
            Recorder recorder = new Recorder(true);
            Map<String, byte[]> payloadBefore = fix.payloadSnapshot();
            String pending = BulkTorrentActions.ACTION_REMOVE + "\n99";
            BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, fix.entries, pending, "99", "1",
                recorder, recorder, recorder);
            Map<String, byte[]> payloadAfter = fix.payloadSnapshot();
            boolean onlyStoppedMetaGone = fix.metadata("seed.torrent").isFile()
                && fix.metadata("partial.torrent").isFile()
                && !fix.metadata("stopped.torrent").exists();
            boolean dirsRemain = new File(fix.root, "partial-data").isDirectory()
                && new File(fix.root, "partial-data/nested").isDirectory()
                && new File(fix.root, "magnet-data").isDirectory();
            return applied.kind == BulkTorrentActions.Kind.APPLIED
                && applied.count == 1
                && applied.failures.isEmpty()
                && BulkTorrentActions.MSG_REMOVED.equals(applied.messageKey)
                && recorder.removed.equals(Arrays.asList("stopped"))
                && onlyStoppedMetaGone
                && sameSnap(payloadBefore, payloadAfter)
                && dirsRemain
                && new File(fix.root, "notes.txt").isFile()
                && Arrays.equals(payloadBefore.get("seed-data.bin"), read(new File(fix.root, "seed-data.bin")))
                && Arrays.equals(payloadBefore.get("partial-data/nested/piece.bin"),
                                 read(new File(fix.root, "partial-data/nested/piece.bin")));
        } finally {
            fix.delete();
        }
    }

    private boolean magnetAndStopped() throws Exception {
        Fixture fix = Fixture.mixed();
        try {
            Recorder recorder = new Recorder(true);
            // Drop the running incomplete and the seeder so this case is stopped + magnet only.
            List<BulkTorrentActions.Entry> subset = new ArrayList<BulkTorrentActions.Entry>();
            subset.add(fix.entries.get(2));
            subset.add(fix.entries.get(3));
            Map<String, byte[]> before = fix.payloadSnapshot();
            BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, subset,
                BulkTorrentActions.ACTION_REMOVE + "\n3", "3", "1",
                recorder, recorder, recorder);
            return applied.count == 1
                && recorder.removed.equals(Arrays.asList("stopped"))
                && !recorder.removed.contains("magnet")
                && !fix.metadata("stopped.torrent").exists()
                && fix.metadata("seed.torrent").exists()
                && sameSnap(before, fix.payloadSnapshot())
                && new File(fix.root, "magnet-data/partial.bin").isFile();
        } finally {
            fix.delete();
        }
    }

    private boolean unsafeMetadataReported() throws Exception {
        File root = Files.createTempDirectory("i2psnark-unsafe-").toFile();
        try {
            File payloadDir = new File(root, "download");
            if (!payloadDir.mkdir())
                return false;
            byte[] keep = bytes(7, 64);
            write(new File(payloadDir, "keep.bin"), keep);
            File inside = new File(payloadDir, "hidden.torrent");
            write(inside, bytes(3, 16));
            File notTorrent = new File(root, "data.bin");
            write(notTorrent, bytes(4, 16));
            File asPayload = new File(root, "same.torrent");
            byte[] same = bytes(5, 32);
            write(asPayload, same);
            File dirNamedTorrent = new File(root, "folder.torrent");
            if (!dirNamedTorrent.mkdir())
                return false;
            write(new File(dirNamedTorrent, "child.bin"), bytes(6, 8));
            File lockedDir = new File(root, "locked");
            if (!lockedDir.mkdir())
                return false;
            File locked = new File(lockedDir, "locked.torrent");
            write(locked, bytes(8, 12));
            if (!lockedDir.setWritable(false))
                return false;

            List<BulkTorrentActions.Entry> entries = new ArrayList<BulkTorrentActions.Entry>();
            entries.add(entry("inside", "Inside", true, false, true, false, false, inside, payloadDir));
            entries.add(entry("notfile", "NotATorrent", true, false, true, false, true, notTorrent, new File(root, "other.bin")));
            entries.add(entry("same", "Same", true, false, true, false, true, asPayload, asPayload));
            entries.add(entry("dir", "Dir", true, false, true, false, false, dirNamedTorrent, new File(root, "elsewhere")));
            entries.add(entry("locked", "Locked", true, false, true, false, false, locked, new File(root, "locked-data.bin")));
            Recorder recorder = new Recorder(true);
            try {
                BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                    "POST", BulkTorrentActions.ACTION_REMOVE, entries,
                    BulkTorrentActions.ACTION_REMOVE + "\n11", "11", "1",
                    recorder, recorder, recorder);
                boolean noRemove = recorder.removed.isEmpty();
                boolean reasons = reasons(applied).contains(BulkTorrentActions.REASON_INSIDE_PAYLOAD)
                    && reasons(applied).contains(BulkTorrentActions.REASON_NOT_FILE)
                    && reasons(applied).contains(BulkTorrentActions.REASON_IS_PAYLOAD)
                    && reasons(applied).contains(BulkTorrentActions.REASON_NO_PERM);
                return applied.kind == BulkTorrentActions.Kind.APPLIED
                    && BulkTorrentActions.MSG_REMOVE_NONE.equals(applied.messageKey)
                    && applied.failures.size() == 5
                    && noRemove
                    && reasons
                    && inside.isFile()
                    && Arrays.equals(keep, read(new File(payloadDir, "keep.bin")))
                    && Arrays.equals(same, read(asPayload))
                    && dirNamedTorrent.isDirectory()
                    && new File(dirNamedTorrent, "child.bin").isFile()
                    && locked.isFile();
            } finally {
                lockedDir.setWritable(true);
            }
        } finally {
            deleteTree(root);
        }
    }

    private boolean deleteFailureReported() throws Exception {
        Fixture fix = Fixture.mixed();
        try {
            final File doomed = fix.metadata("stopped.torrent");
            byte[] seedMeta = read(doomed);
            byte[] runningMeta = read(fix.metadata("seed.torrent"));
            Map<String, byte[]> payloadBefore = fix.payloadSnapshot();
            Recorder recorder = new Recorder(true) {
                @Override
                public boolean delete(File file) {
                    deleted.add(file.getPath());
                    if (file.getName().equals("stopped.torrent"))
                        return false;
                    return file.delete();
                }
            };
            BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, fix.entries,
                BulkTorrentActions.ACTION_REMOVE + "\n15", "15", "2",
                recorder, recorder, recorder);
            boolean seedFailed = false;
            for (int i = 0; i < applied.failures.size(); i++) {
                BulkTorrentActions.Failure failure = applied.failures.get(i);
                if ("Stopped".equals(failure.name) && BulkTorrentActions.REASON_DELETE_FAILED.equals(failure.reason))
                    seedFailed = true;
            }
            return applied.count == 1
                && BulkTorrentActions.MSG_REMOVE_PARTIAL.equals(applied.messageKey)
                && seedFailed
                && doomed.isFile()
                && Arrays.equals(seedMeta, read(doomed))
                && Arrays.equals(runningMeta, read(fix.metadata("seed.torrent")))
                && fix.metadata("partial.torrent").isFile()
                && sameSnap(payloadBefore, fix.payloadSnapshot())
                && recorder.removed.equals(Arrays.asList("stopped"))
                && !recorder.removed.contains("seed")
                && !recorder.removed.contains("partial");
        } finally {
            fix.delete();
        }
    }

    private boolean callbackFailure() throws Exception {
        Fixture fix = Fixture.mixed();
        try {
            Recorder recorder = new Recorder(true) {
                @Override
                public void removeFromList(BulkTorrentActions.Entry entry) {
                    if ("stopped".equals(entry.id))
                        throw new IllegalStateException("disk busy");
                    super.removeFromList(entry);
                }
            };
            BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, fix.entries,
                BulkTorrentActions.ACTION_REMOVE + "\n4", "4", "1",
                recorder, recorder, recorder);
            boolean saw = false;
            for (int i = 0; i < applied.failures.size(); i++) {
                if ("Stopped".equals(applied.failures.get(i).name)
                        && "disk busy".equals(applied.failures.get(i).reason))
                    saw = true;
            }
            return applied.kind == BulkTorrentActions.Kind.APPLIED
                && BulkTorrentActions.MSG_REMOVE_NONE.equals(applied.messageKey)
                && saw
                && fix.metadata("partial.torrent").isFile()
                && fix.metadata("seed.torrent").isFile()
                && fix.metadata("stopped.torrent").isFile()
                && !recorder.removed.contains("stopped")
                && !recorder.removed.contains("partial")
                && !recorder.removed.contains("magnet");
        } finally {
            fix.delete();
        }
    }

    private boolean wrongActionToken() throws Exception {
        Fixture fix = Fixture.mixed();
        try {
            Recorder recorder = new Recorder(true);
            Map<String, byte[]> before = fix.snapshot();
            BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, fix.entries,
                BulkTorrentActions.ACTION_STOP + "\n55", "55", "1",
                recorder, recorder, recorder);
            return applied.kind == BulkTorrentActions.Kind.NEEDS_CONFIRM
                && recorder.removed.isEmpty()
                && sameSnap(before, fix.snapshot());
        } finally {
            fix.delete();
        }
    }

    private boolean symlinkDoesNotDeletePayload() throws Exception {
        File root = Files.createTempDirectory("i2psnark-link-").toFile();
        try {
            File payload = new File(root, "movie.bin");
            byte[] data = bytes(9, 50);
            write(payload, data);
            File link = new File(root, "movie.torrent");
            try {
                Files.createSymbolicLink(link.toPath(), payload.toPath());
            } catch (IOException ioe) {
                // This environment cannot make symlinks. The path-equality check still covers the case.
                return true;
            }
            List<BulkTorrentActions.Entry> entries = new ArrayList<BulkTorrentActions.Entry>();
            entries.add(entry("link", "Link", true, false, true, false, true, link, payload));
            Recorder recorder = new Recorder(true);
            BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, entries,
                BulkTorrentActions.ACTION_REMOVE + "\n1", "1", "2",
                recorder, recorder, recorder);
            return payload.isFile()
                && Arrays.equals(data, read(payload))
                && !recorder.removed.contains("link")
                && applied.failures.size() == 1
                && link.exists();
        } finally {
            deleteTree(root);
        }
    }

    private boolean mixedOnlyStoppedRemoved() throws Exception {
        File root = Files.createTempDirectory("i2psnark-mixed-").toFile();
        try {
            write(new File(root, "seed.torrent"), bytes(1, 20));
            write(new File(root, "seed-data.bin"), bytes(2, 40));
            write(new File(root, "down.torrent"), bytes(3, 20));
            write(new File(root, "down-data/part.bin"), bytes(4, 40));
            write(new File(root, "stalled.torrent"), bytes(5, 20));
            write(new File(root, "stalled-data.bin"), bytes(6, 40));
            write(new File(root, "recheck.torrent"), bytes(7, 20));
            write(new File(root, "recheck-data.bin"), bytes(8, 40));
            write(new File(root, "starting.torrent"), bytes(9, 20));
            write(new File(root, "starting-data.bin"), bytes(10, 40));
            write(new File(root, "alloc.torrent"), bytes(11, 20));
            write(new File(root, "alloc-data.bin"), bytes(12, 40));
            write(new File(root, "stopped-a.torrent"), bytes(13, 20));
            write(new File(root, "stopped-a-data.bin"), bytes(14, 40));
            write(new File(root, "stopped-b.torrent"), bytes(15, 20));
            write(new File(root, "stopped-b-data/nested.bin"), bytes(16, 40));
            write(new File(root, "magnet-data/partial.bin"), bytes(17, 30));
            write(new File(root, "notes.txt"), bytes(18, 16));
            List<BulkTorrentActions.Entry> entries = new ArrayList<BulkTorrentActions.Entry>();
            // Running complete (seeding). Zero rate is not represented; stopped is the only selector.
            entries.add(entry("seed", "Seed", false, false, true, false, true,
                              new File(root, "seed.torrent"), new File(root, "seed-data.bin")));
            // Downloading, not stopped.
            entries.add(entry("down", "Down", false, false, true, false, false,
                              new File(root, "down.torrent"), new File(root, "down-data")));
            // Stalled in the UI is still running (stopped flag false). Do not infer it from speed.
            entries.add(entry("stalled", "Stalled", false, false, true, false, false,
                              new File(root, "stalled.torrent"), new File(root, "stalled-data.bin")));
            // Recheck keeps the stopped flag in I2PSnark. checking excludes it.
            entries.add(entry("recheck", "Recheck", true, true, true, false, false,
                              new File(root, "recheck.torrent"), new File(root, "recheck-data.bin")));
            // Startup in progress: stopped flag is still true. starting excludes it.
            entries.add(entry("starting", "Starting", true, false, true, false, false,
                              new File(root, "starting.torrent"), new File(root, "starting-data.bin"), true, false));
            entries.add(entry("alloc", "Alloc", true, false, true, false, false,
                              new File(root, "alloc.torrent"), new File(root, "alloc-data.bin"), false, true));
            entries.add(entry("stopped-a", "StoppedA", true, false, true, false, true,
                              new File(root, "stopped-a.torrent"), new File(root, "stopped-a-data.bin")));
            entries.add(entry("stopped-b", "StoppedB", true, false, true, false, false,
                              new File(root, "stopped-b.torrent"), new File(root, "stopped-b-data")));
            // Stopped magnet and a running fetch have no removable .torrent metadata.
            entries.add(entry("magnet", "Magnet", true, false, false, true, false,
                              null, new File(root, "magnet-data")));
            entries.add(entry("fetch", "Fetch", false, false, false, true, false, null, null));
            Map<String, byte[]> payloadBefore = new LinkedHashMap<String, byte[]>();
            snapTree(root, "", payloadBefore);
            Recorder recorder = new Recorder(true);
            BulkTorrentActions.Outcome ask = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, entries, null, null, "70",
                recorder, recorder, recorder);
            BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, entries, ask.pendingToStore, "70", "1",
                recorder, recorder, recorder);
            Map<String, byte[]> after = new LinkedHashMap<String, byte[]>();
            snapTree(root, "", after);
            boolean othersRemain = new File(root, "seed.torrent").isFile()
                && new File(root, "down.torrent").isFile()
                && new File(root, "stalled.torrent").isFile()
                && new File(root, "recheck.torrent").isFile()
                && new File(root, "starting.torrent").isFile()
                && new File(root, "alloc.torrent").isFile()
                && !new File(root, "stopped-a.torrent").exists()
                && !new File(root, "stopped-b.torrent").exists();
            payloadBefore.remove("stopped-a.torrent");
            payloadBefore.remove("stopped-b.torrent");
            return ask.count == 2
                && applied.count == 2
                && applied.failures.isEmpty()
                && recorder.removed.equals(Arrays.asList("stopped-a", "stopped-b"))
                && othersRemain
                && sameSnap(payloadBefore, after)
                && new File(root, "magnet-data/partial.bin").isFile()
                && new File(root, "down-data/part.bin").isFile()
                && new File(root, "stopped-b-data/nested.bin").isFile()
                && new File(root, "notes.txt").isFile();
        } finally {
            deleteTree(root);
        }
    }

    private boolean restartedSinceConfirmation() throws Exception {
        File root = Files.createTempDirectory("i2psnark-restart-").toFile();
        try {
            write(new File(root, "keep.torrent"), bytes(1, 24));
            write(new File(root, "keep-data.bin"), bytes(2, 48));
            write(new File(root, "gone.torrent"), bytes(3, 24));
            write(new File(root, "gone-data.bin"), bytes(4, 48));
            BulkTorrentActions.Entry restarted = entry("keep", "Keep", true, false, true, false, true,
                                                       new File(root, "keep.torrent"), new File(root, "keep-data.bin"));
            BulkTorrentActions.Entry still = entry("gone", "Gone", true, false, true, false, false,
                                                   new File(root, "gone.torrent"), new File(root, "gone-data.bin"));
            List<BulkTorrentActions.Entry> entries = new ArrayList<BulkTorrentActions.Entry>();
            entries.add(restarted);
            entries.add(still);
            final List<String> liveStopped = new ArrayList<String>();
            liveStopped.add("gone");
            Recorder recorder = new Recorder(true);
            BulkTorrentActions.StillStopped live = new BulkTorrentActions.StillStopped() {
                public boolean isExplicitlyStopped(BulkTorrentActions.Entry entry) {
                    return liveStopped.contains(entry.id);
                }
            };
            byte[] keepMeta = read(new File(root, "keep.torrent"));
            byte[] keepData = read(new File(root, "keep-data.bin"));
            BulkTorrentActions.Outcome applied = BulkTorrentActions.dispatch(
                "POST", BulkTorrentActions.ACTION_REMOVE, entries,
                BulkTorrentActions.ACTION_REMOVE + "\n81", "81", "2",
                recorder, recorder, recorder, live);
            boolean skipped = false;
            for (int i = 0; i < applied.failures.size(); i++) {
                BulkTorrentActions.Failure failure = applied.failures.get(i);
                if ("Keep".equals(failure.name)
                        && BulkTorrentActions.REASON_NO_LONGER_STOPPED.equals(failure.reason))
                    skipped = true;
            }
            return applied.count == 1
                && BulkTorrentActions.MSG_REMOVED.equals(applied.messageKey)
                && skipped
                && recorder.removed.equals(Arrays.asList("gone"))
                && !recorder.removed.contains("keep")
                && new File(root, "keep.torrent").isFile()
                && Arrays.equals(keepMeta, read(new File(root, "keep.torrent")))
                && Arrays.equals(keepData, read(new File(root, "keep-data.bin")))
                && !new File(root, "gone.torrent").exists()
                && new File(root, "gone-data.bin").isFile();
        } finally {
            deleteTree(root);
        }
    }

    private boolean markup() {
        String stop = BulkTorrentActions.toolbarButton(
            BulkTorrentActions.ACTION_STOP, "/themes/dark/images/stop_all.png",
            "Stop all seeding torrents. Downloaded files are not deleted.");
        String remove = BulkTorrentActions.toolbarButton(
            BulkTorrentActions.ACTION_REMOVE, "/themes/dark/images/remove.png",
            "Remove stopped torrents. Downloaded files and partial data directories are not deleted.",
            "Remove stopped torrents");
        String form = BulkTorrentActions.confirmForm(
            BulkTorrentActions.ACTION_REMOVE, "99", "SNA1",
            "Remove stopped torrents? Data <kept> & will not be deleted.",
            "Confirm", "Cancel");
        return stop.contains("type=\"submit\"")
            && stop.contains("value=\"StopSeeding\"")
            && stop.contains("stop_all.png")
            && stop.contains(">*</span>")
            && stop.contains("aria-label=")
            && stop.contains("title=")
            && !stop.contains("href=")
            && remove.contains("remove.png")
            && remove.contains("value=\"RemoveStopped\"")
            && remove.contains("Remove stopped torrents")
            && !remove.contains("Remove all")
            && remove.contains(">*</span>")
            && !remove.contains("href=")
            && form.contains("method=\"POST\"")
            && !form.contains("method=\"GET\"")
            && !form.contains("href=")
            && form.contains("name=\"nonce\"")
            && form.contains("name=\"confirm\" value=\"99\"")
            && form.contains("value=\"RemoveStopped\"")
            && form.contains("Remove stopped torrents")
            && form.contains("value=\"CancelBulk\"")
            && form.contains("Confirm")
            && form.contains("Cancel")
            && form.contains("will not be deleted")
            && form.contains("&lt;kept&gt;")
            && form.contains("&amp;")
            && !form.contains("<kept>");
    }

    private static boolean sameSnap(Map<String, byte[]> left, Map<String, byte[]> right) {
        if (left.size() != right.size())
            return false;
        for (Map.Entry<String, byte[]> entry : left.entrySet()) {
            if (!right.containsKey(entry.getKey()))
                return false;
            byte[] a = entry.getValue();
            byte[] b = right.get(entry.getKey());
            if (a == null || b == null) {
                if (a != b)
                    return false;
            } else if (!Arrays.equals(a, b)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> reasons(BulkTorrentActions.Outcome outcome) {
        List<String> reasons = new ArrayList<String>();
        for (int i = 0; i < outcome.failures.size(); i++)
            reasons.add(outcome.failures.get(i).reason);
        return reasons;
    }

    private void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            failures.add(name);
            System.out.println("FAIL " + name);
        }
    }

    private static BulkTorrentActions.Entry entry(String id, String name, boolean stopped, boolean checking,
                                                  boolean hasMeta, boolean magnet, boolean complete,
                                                  File metadata, File payload) {
        return entry(id, name, stopped, checking, hasMeta, magnet, complete, metadata, payload, false, false);
    }

    private static BulkTorrentActions.Entry entry(String id, String name, boolean stopped, boolean checking,
                                                  boolean hasMeta, boolean magnet, boolean complete,
                                                  File metadata, File payload, boolean starting, boolean allocating) {
        return new BulkTorrentActions.Entry(id, name, stopped, checking, hasMeta, magnet, complete,
                                            metadata, payload, starting, allocating);
    }

    private static byte[] bytes(int seed, int len) {
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++)
            data[i] = (byte) (seed * 17 + i * 13);
        data[0] = 0;
        data[len - 1] = (byte) 0xff;
        return data;
    }

    private static void write(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("mkdir " + parent);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static byte[] read(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static void deleteTree(File file) {
        File[] kids = file.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++)
                deleteTree(kids[i]);
        }
        file.delete();
    }

    /** Mixed complete, incomplete, stopped, and magnet fixtures under one temp root. */
    private static final class Fixture {
        final File root;
        final List<BulkTorrentActions.Entry> entries = new ArrayList<BulkTorrentActions.Entry>();

        private Fixture(File root) {
            this.root = root;
        }

        static Fixture mixed() throws IOException {
            File root = Files.createTempDirectory("i2psnark-bulk-").toFile();
            Fixture fix = new Fixture(root);
            write(new File(root, "seed.torrent"), bytes(1, 40));
            write(new File(root, "seed-data.bin"), bytes(2, 128));
            write(new File(root, "partial.torrent"), bytes(3, 40));
            write(new File(root, "partial-data/part.bin"), bytes(4, 80));
            write(new File(root, "partial-data/nested/piece.bin"), bytes(5, 90));
            write(new File(root, "stopped.torrent"), bytes(6, 24));
            write(new File(root, "stopped-data.bin"), bytes(7, 48));
            write(new File(root, "magnet-data/partial.bin"), bytes(8, 36));
            write(new File(root, "notes.txt"), bytes(9, 20));
            fix.entries.add(fix.fileEntry("seed", "Seed", false, false, true, false, true,
                                          "seed.torrent", "seed-data.bin"));
            fix.entries.add(fix.fileEntry("partial", "Down", false, false, true, false, false,
                                          "partial.torrent", "partial-data"));
            fix.entries.add(fix.fileEntry("stopped", "Stopped", true, false, true, false, true,
                                          "stopped.torrent", "stopped-data.bin"));
            fix.entries.add(fix.fileEntry("magnet", "Magnet", false, false, false, true, false,
                                          null, "magnet-data"));
            return fix;
        }

        private BulkTorrentActions.Entry fileEntry(String id, String name, boolean stopped, boolean checking,
                                                   boolean hasMeta, boolean magnet, boolean complete,
                                                   String metadata, String payload) {
            File meta = metadata == null ? null : new File(root, metadata);
            File data = payload == null ? null : new File(root, payload);
            return entry(id, name, stopped, checking, hasMeta, magnet, complete, meta, data);
        }

        File metadata(String name) {
            return new File(root, name);
        }

        Map<String, byte[]> snapshot() throws IOException {
            Map<String, byte[]> snap = new LinkedHashMap<String, byte[]>();
            snapTree(root, "", snap);
            return snap;
        }

        /** Payload, partial directories, and the unrelated notes file. Not .torrent metadata. */
        Map<String, byte[]> payloadSnapshot() throws IOException {
            Map<String, byte[]> snap = snapshot();
            List<String> drop = new ArrayList<String>();
            for (String name : snap.keySet()) {
                if (name.endsWith(".torrent"))
                    drop.add(name);
            }
            for (int i = 0; i < drop.size(); i++)
                snap.remove(drop.get(i));
            return snap;
        }

        void delete() {
            deleteTree(root);
        }
    }

    private static void snapTree(File file, String rel, Map<String, byte[]> out) throws IOException {
        if (file.isDirectory()) {
            out.put(rel + "/", null);
            File[] kids = file.listFiles();
            if (kids == null)
                return;
            Arrays.sort(kids);
            for (int i = 0; i < kids.length; i++) {
                String child = rel.length() == 0 ? kids[i].getName() : rel + "/" + kids[i].getName();
                snapTree(kids[i], child, out);
            }
        } else if (file.isFile()) {
            out.put(rel, read(file));
        }
    }

    private static class Recorder implements BulkTorrentActions.Stopper, BulkTorrentActions.Remover,
                                             BulkTorrentActions.FileDeleter {
        final List<String> stopped = new ArrayList<String>();
        final List<String> removed = new ArrayList<String>();
        final List<String> deleted = new ArrayList<String>();
        private final boolean realDelete;

        Recorder() {
            this(false);
        }

        Recorder(boolean realDelete) {
            this.realDelete = realDelete;
        }

        public void stopLeavingOnList(BulkTorrentActions.Entry entry) {
            stopped.add(entry.id);
        }

        public void removeFromList(BulkTorrentActions.Entry entry) {
            removed.add(entry.id);
        }

        public boolean delete(File file) {
            deleted.add(file.getPath());
            if (!realDelete)
                return false;
            return file.delete();
        }
    }
}
