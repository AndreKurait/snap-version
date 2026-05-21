package io.kurait.smile.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import io.kurait.smile.codec.MetadataCodec;
import io.kurait.smile.codec.SmileJson;
import io.kurait.smile.repo.SnapshotRepo;
import io.kurait.smile.s3.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Two-pane terminal UI for browsing + editing an ES/OS snapshot repository.
 *
 * <pre>
 *   +-------------------+------------------------------------+
 *   | Blobs             | JSON view of currently-loaded blob |
 *   |  index.latest     |                                    |
 *   |  index-N (JSON)   |  edit freely; Ctrl-S to save back  |
 *   |  meta-*.dat       |                                    |
 *   |  snap-*.dat       |                                    |
 *   |  indices/<id>/... |                                    |
 *   +-------------------+------------------------------------+
 *   |  status bar / shortcuts                                 |
 *   +---------------------------------------------------------+
 * </pre>
 *
 * Keys:
 *   Tab / Shift-Tab : move focus between panes
 *   Enter (in list) : load the selected blob into the editor
 *   Ctrl-S          : re-encode + upload the editor contents to S3
 *   Ctrl-R          : reload the selected blob from S3 (discards edits)
 *   Ctrl-Q          : quit
 */
public class SnapshotTui {

    private static final Logger log = LoggerFactory.getLogger(SnapshotTui.class);

    private final SnapshotStore store;
    private final SnapshotRepo repo;
    private final ObjectMapper jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ActionListBox blobList;
    private TextBox editor;
    private Label statusLabel;

    private List<BlobEntry> currentEntries = new ArrayList<>();
    private BlobEntry selected;
    /** Codec / compression info needed to re-emit a loaded {@code .dat} blob. */
    private MetadataCodec.Decoded loadedDecoded;
    /** True when the loaded blob is the {@code index-N} plain-JSON manifest (no codec frame). */
    private boolean loadedIsPlainJson;

    public SnapshotTui(SnapshotStore store) {
        this.store = store;
        this.repo = new SnapshotRepo(store);
    }

    public void run() throws IOException {
        try (Terminal terminal = new DefaultTerminalFactory().createTerminal();
             Screen screen = new TerminalScreen(terminal)) {
            screen.startScreen();

            BasicWindow window = new BasicWindow("smile-snapshot-editor — " + store.describe());
            window.setHints(List.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS));

            Panel root = new Panel(new BorderLayout());

            // Left: blob list
            blobList = new ActionListBox();
            Panel leftPane = new Panel(new LinearLayout())
                    .addComponent(blobList.withBorder(Borders.singleLineBevel("Blobs")));

            // Right: JSON editor (multi-line text box)
            editor = new TextBox(new TerminalSize(80, 30), "(select a blob and press Enter)")
                    .setReadOnly(false);
            Panel rightPane = new Panel(new LinearLayout())
                    .addComponent(editor.withBorder(Borders.singleLineBevel("JSON")));

            // Center: split — left ~30 cols, right takes the rest
            Panel center = new Panel(new LinearLayout(com.googlecode.lanterna.gui2.Direction.HORIZONTAL));
            leftPane.setPreferredSize(new TerminalSize(38, 30));
            center.addComponent(leftPane);
            center.addComponent(rightPane);

            // Bottom: status bar
            statusLabel = new Label(buildStatus());
            statusLabel.setForegroundColor(TextColor.ANSI.WHITE);
            Panel bottom = new Panel(new LinearLayout())
                    .addComponent(statusLabel);

            root.addComponent(center, BorderLayout.Location.CENTER);
            root.addComponent(bottom, BorderLayout.Location.BOTTOM);
            window.setComponent(root);

            // Populate the blob list
            refreshBlobList();

            // Global key bindings
            window.addWindowListener(new com.googlecode.lanterna.gui2.WindowListenerAdapter() {
                @Override
                public void onUnhandledInput(Window basePane, KeyStroke key, java.util.concurrent.atomic.AtomicBoolean handled) {
                    if (key.isCtrlDown() && key.getKeyType() == KeyType.Character) {
                        char c = key.getCharacter();
                        if (c == 'q' || c == 'Q') {
                            basePane.close();
                            handled.set(true);
                        } else if (c == 's' || c == 'S') {
                            saveCurrentBlob();
                            handled.set(true);
                        } else if (c == 'r' || c == 'R') {
                            reloadCurrent();
                            handled.set(true);
                        } else if (c == 'l' || c == 'L') {
                            refreshBlobList();
                            handled.set(true);
                        }
                    }
                }
            });

            MultiWindowTextGUI gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(),
                    new EmptySpace(TextColor.ANSI.BLUE));
            gui.addWindowAndWait(window);
        }
    }

    /* -------------------------- list refresh -------------------------- */

    private void refreshBlobList() {
        try {
            currentEntries.clear();
            blobList.clearItems();

            // 1. index.latest
            try {
                long gen = repo.readIndexLatest();
                if (gen >= 0) {
                    add(new BlobEntry("index.latest (gen=" + gen + ")", SnapshotRepo.INDEX_LATEST, BlobType.INDEX_LATEST));
                    add(new BlobEntry("index-" + gen + " (JSON manifest)", "index-" + gen, BlobType.INDEX_N));
                }
            } catch (Exception e) {
                statusLabel.setText("ERROR reading index.latest: " + e.getMessage());
            }

            // 2. enumerate all .dat files at the repo root (meta-*, snap-*)
            for (String name : store.listFilesAtLevel("")) {
                if (name.startsWith("meta-") && name.endsWith(".dat")) {
                    add(new BlobEntry("  " + name + "  [global metadata]", name, BlobType.GLOBAL_META));
                } else if (name.startsWith("snap-") && name.endsWith(".dat")) {
                    add(new BlobEntry("  " + name + "  [snapshot info]", name, BlobType.SNAPSHOT_INFO));
                }
            }

            // 3. enumerate indices/<id>/...
            try {
                for (String idxId : listIndexUuids()) {
                    add(new BlobEntry("indices/" + idxId + "/", null, BlobType.HEADING));
                    for (String f : store.listFilesAtLevel("indices/" + idxId + "/")) {
                        if (f.startsWith("meta-") && f.endsWith(".dat")) {
                            String key = "indices/" + idxId + "/" + f;
                            add(new BlobEntry("  " + f + "  [index meta]", key, BlobType.INDEX_META));
                        }
                    }
                    // Per-shard
                    for (String shardDir : listShardDirs(idxId)) {
                        for (String f : store.listFilesAtLevel("indices/" + idxId + "/" + shardDir + "/")) {
                            if (f.startsWith("snap-") && f.endsWith(".dat")) {
                                String key = "indices/" + idxId + "/" + shardDir + "/" + f;
                                add(new BlobEntry("  " + shardDir + "/" + f + "  [shard meta]", key, BlobType.SHARD_META));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("indices enumeration failed", e);
            }

            statusLabel.setText(buildStatus() + "  | " + currentEntries.size() + " blobs");
        } catch (Exception e) {
            statusLabel.setText("Refresh failed: " + e.getMessage());
        }
    }

    private void add(BlobEntry e) {
        currentEntries.add(e);
        if (e.type == BlobType.HEADING) {
            blobList.addItem(e.label, () -> { /* heading - no action */ });
        } else {
            blobList.addItem(e.label, () -> loadBlob(e));
        }
    }

    private List<String> listIndexUuids() {
        List<String> out = new ArrayList<>();
        try {
            for (String key : store.list("indices/")) {
                int slash1 = key.indexOf('/');
                int slash2 = key.indexOf('/', slash1 + 1);
                if (slash1 >= 0 && slash2 > slash1) {
                    String uuid = key.substring(slash1 + 1, slash2);
                    if (!out.contains(uuid)) out.add(uuid);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<String> listShardDirs(String indexUuid) {
        // Shards are integer-named subdirs. Inspect via list and split paths.
        List<String> out = new ArrayList<>();
        try {
            for (String key : store.list("indices/" + indexUuid + "/")) {
                String rest = key.substring(("indices/" + indexUuid + "/").length());
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    String dir = rest.substring(0, slash);
                    if (dir.matches("\\d+") && !out.contains(dir)) out.add(dir);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /* -------------------------- load / save -------------------------- */

    private void loadBlob(BlobEntry e) {
        try {
            selected = e;
            loadedDecoded = null;
            loadedIsPlainJson = false;
            byte[] raw = store.get(e.key);

            String json;
            switch (e.type) {
                case INDEX_LATEST -> {
                    if (raw.length >= 8) {
                        long gen = ((long)(raw[0] & 0xFF) << 56) | ((long)(raw[1] & 0xFF) << 48)
                                 | ((long)(raw[2] & 0xFF) << 40) | ((long)(raw[3] & 0xFF) << 32)
                                 | ((long)(raw[4] & 0xFF) << 24) | ((long)(raw[5] & 0xFF) << 16)
                                 | ((long)(raw[6] & 0xFF) <<  8) | ((long)(raw[7] & 0xFF));
                        json = "{\n  \"_note\": \"index.latest is an 8-byte BE long. Editing here saves it back as raw bytes.\",\n  \"generation\": " + gen + "\n}";
                    } else {
                        json = "{}";
                    }
                    loadedIsPlainJson = true;
                }
                case INDEX_N -> {
                    JsonNode tree = jsonMapper.readTree(raw);
                    json = jsonMapper.writeValueAsString(tree);
                    loadedIsPlainJson = true;
                }
                default -> {
                    String codec = guessCodec(e);
                    loadedDecoded = MetadataCodec.decode(raw, codec);
                    json = SmileJson.smileToJson(loadedDecoded.smileBytes(), true);
                }
            }
            editor.setText(json);
            statusLabel.setText("Loaded: " + e.key + " (" + raw.length + " bytes)"
                    + (loadedDecoded != null ? "  codec=" + loadedDecoded.codecName() + " compressed=" + loadedDecoded.compressed() : ""));
        } catch (Exception ex) {
            log.error("loadBlob failed for {}", e.key, ex);
            editor.setText("ERROR loading " + e.key + ":\n" + ex.toString());
            statusLabel.setText("Load failed: " + ex.getMessage());
        }
    }

    private void saveCurrentBlob() {
        if (selected == null) {
            statusLabel.setText("Nothing loaded.");
            return;
        }
        try {
            String text = editor.getText();
            byte[] toUpload;
            switch (selected.type) {
                case INDEX_LATEST -> {
                    JsonNode t = new ObjectMapper().readTree(text);
                    long gen = t.path("generation").asLong();
                    toUpload = new byte[8];
                    for (int i = 7; i >= 0; i--) { toUpload[i] = (byte)(gen & 0xFF); gen >>>= 8; }
                }
                case INDEX_N -> {
                    // Re-emit pretty-printed JSON for readability; ES is lenient about whitespace here.
                    JsonNode tree = new ObjectMapper().readTree(text);
                    toUpload = new ObjectMapper().writeValueAsBytes(tree);
                }
                default -> {
                    if (loadedDecoded == null) {
                        statusLabel.setText("Internal: missing decoded info");
                        return;
                    }
                    byte[] smile = SmileJson.jsonToSmile(text);
                    toUpload = MetadataCodec.encode(smile, loadedDecoded);
                }
            }
            store.put(selected.key, toUpload);
            statusLabel.setText("Saved " + toUpload.length + " bytes -> " + selected.key);
        } catch (Exception ex) {
            log.error("saveCurrentBlob failed", ex);
            try {
                MessageDialog.showMessageDialog((WindowBasedTextGUI) editor.getTextGUI(),
                        "Save failed", ex.toString(), MessageDialogButton.OK);
            } catch (Exception ignored) { /* if no GUI yet, just status */ }
            statusLabel.setText("Save failed: " + ex.getMessage());
        }
    }

    private void reloadCurrent() {
        if (selected != null) loadBlob(selected);
    }

    private static String guessCodec(BlobEntry e) {
        return switch (e.type) {
            case GLOBAL_META -> SnapshotRepo.CODEC_GLOBAL_METADATA;
            case INDEX_META  -> SnapshotRepo.CODEC_INDEX_METADATA;
            case SNAPSHOT_INFO, SHARD_META -> SnapshotRepo.CODEC_SNAPSHOT;
            default -> null;
        };
    }

    private String buildStatus() {
        return "Ctrl-S=save  Ctrl-R=reload  Ctrl-L=relist  Ctrl-Q=quit  Tab=switch panes";
    }

    /* -------------------------- model -------------------------- */

    public enum BlobType {
        INDEX_LATEST, INDEX_N, GLOBAL_META, SNAPSHOT_INFO, INDEX_META, SHARD_META, HEADING
    }

    public static class BlobEntry {
        final String label;
        final String key;
        final BlobType type;

        BlobEntry(String label, String key, BlobType type) {
            this.label = label;
            this.key = key;
            this.type = type;
        }
    }
}
