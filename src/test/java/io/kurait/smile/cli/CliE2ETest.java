package io.kurait.smile.cli;

import io.kurait.smile.Main;
import io.kurait.smile.testsupport.FakeRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the actual CLI commands the same way a user would, against a synthetic
 * local snapshot repo. Each test is a runnable example of one supported workflow.
 *
 * <p>If you want to know what args to pass and what output to expect, read these tests.
 */
class CliE2ETest {

    /* ---------- usage example: just inventory the repo, no edits ---------- */

    @Test
    void example_versionShow_listsSnapshotsAndDetectsVersions(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.4");
        FakeRepo.appendSnapshot(tmp, "snap-B", "uuid-B", "2.15.0", 1);

        Captured cap = run(
                "version",
                "--repo", tmp.toString(),
                "--show"
        );

        assertThat(cap.exitCode).isZero();
        assertThat(cap.stdout)
                .contains("Repository: gen=1")
                .contains("snap-A").contains("uuid-A").contains("2.19.4")
                .contains("136408227 (2.19.4)")
                .contains("snap-B").contains("uuid-B").contains("2.15.0")
                .contains("136367827 (2.15.0)");
    }

    /* ---------- usage example: rewrite all snapshots in one command ---------- */

    @Test
    void example_versionTo_rewritesAllSnapshotsAtOnce(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-foo", "uuid-foo", "2.19.4");

        Captured cap = run(
                "version",
                "--repo", tmp.toString(),
                "--to", "2.19.0",
                "--yes"
        );

        assertThat(cap.exitCode).isZero();
        assertThat(cap.stdout)
                .contains("Planned rewrite:")
                .contains("target version           : 2.19.0")
                .contains("target version_id (OS)   : 136407827")
                .contains("target version_id (ES)   : 2190099")
                .contains("Applied changes:")
                .contains("version: 2.19.4 -> 2.19.0")
                .contains("version_id: 136408227 -> 136407827");
    }

    /* ---------- usage example: pick specific snapshots by name ---------- */

    @Test
    void example_snapshotFlag_restrictsScopeToOneSnapshot(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.4");
        FakeRepo.appendSnapshot(tmp, "snap-B", "uuid-B", "2.19.4", 1);

        Captured cap = run(
                "version",
                "--repo", tmp.toString(),
                "--to", "2.15.0",
                "--snapshot", "snap-A",
                "--yes"
        );

        assertThat(cap.exitCode).isZero();
        // Inventory table shows BOTH snapshots; only snap-A is in the "Applied changes" section.
        assertThat(cap.stdout).contains("snap-A").contains("snap-B");
        // Find the "Applied changes:" section and assert only uuid-A appears there.
        String applied = cap.stdout.substring(cap.stdout.indexOf("Applied changes:"));
        assertThat(applied).contains("uuid-A").doesNotContain("uuid-B");
    }

    /* ---------- usage example: bad target version is rejected ---------- */

    @Test
    void example_invalidTargetVersion_failsLoudly(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-foo", "uuid-foo", "2.19.4");

        Captured cap = run(
                "version",
                "--repo", tmp.toString(),
                "--to", "blah",
                "--yes"
        );

        assertThat(cap.exitCode).isNotZero();
        assertThat(cap.stderr).contains("not a major.minor.revision string");
    }

    /* ---------- usage example: missing repo flag prints picocli usage ---------- */

    @Test
    void example_missingRepo_printsUsage(@TempDir Path tmp) throws Exception {
        Captured cap = run("version", "--show");
        assertThat(cap.exitCode).isNotZero();
        assertThat(cap.stderr).contains("Missing required option").contains("--repo");
    }

    /* ---------- usage example: ls subcommand on a local repo ---------- */

    @Test
    void example_lsSubcommand_listsManifestContents(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-foo", "uuid-foo", "2.19.4");

        Captured cap = run("ls", "--repo", tmp.toString());
        assertThat(cap.exitCode).isZero();
        assertThat(cap.stdout)
                .contains("Repository:")
                .contains("snap-foo")
                .contains("uuid-foo")
                .contains("2.19.4");
    }

    /* ---------- usage example: cat subcommand decodes a smile blob ---------- */

    @Test
    void example_catSubcommand_decodesBlobToJson(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-foo", "uuid-foo", "2.19.4");

        Captured cap = run(
                "cat",
                "--repo", tmp.toString(),
                "--key", "snap-uuid-foo.dat"
        );
        assertThat(cap.exitCode).isZero();
        // The cat command prints decoded JSON to stdout
        assertThat(cap.stdout)
                .contains("\"snapshot\"")
                .contains("\"version_id\" : 136408227")
                .contains("\"uuid\" : \"uuid-foo\"");
    }

    /* =================================================================== */
    /*                     plumbing                                          */
    /* =================================================================== */

    /** Captured stdout/stderr/exit from a single CLI invocation. */
    record Captured(int exitCode, String stdout, String stderr) {}

    /** Run the CLI just like `bin/smile-snapshot-editor` would, in-process. */
    private Captured run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

            int exit = new CommandLine(new Main())
                    .setOut(new java.io.PrintWriter(System.out, true))
                    .setErr(new java.io.PrintWriter(System.err, true))
                    .execute(args);

            return new Captured(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }
}
