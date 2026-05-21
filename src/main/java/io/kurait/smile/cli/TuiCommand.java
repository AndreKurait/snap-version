package io.kurait.smile.cli;

import io.kurait.smile.s3.SnapshotStore;
import io.kurait.smile.s3.StoreOpener;
import io.kurait.smile.tui.SnapshotTui;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

@Command(name = "tui",
        description = "Open the interactive terminal UI: browse blobs in the left pane, " +
                "edit the JSON view in the right pane, Ctrl-S to upload changes back.")
public class TuiCommand implements Callable<Integer> {

    @Mixin S3Options s3opts = new S3Options();

    @Override
    public Integer call() throws Exception {
        // Lanterna needs reflection + JNI that's painful to wire into native-image.
        // The native binary builds without the TUI; the JVM dist still has it.
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            System.err.println("snap-version tui is not available in the native binary build.");
            System.err.println("Use the JVM distribution: download snap-version-<version>.tar from the GitHub release.");
            return 2;
        }
        try (SnapshotStore store = StoreOpener.open(s3opts.repo, s3opts.region, s3opts.endpoint,
                s3opts.accessKey, s3opts.secretKey, s3opts.pathStyle, s3opts.profile)) {
            new SnapshotTui(store).run();
            return 0;
        }
    }
}
