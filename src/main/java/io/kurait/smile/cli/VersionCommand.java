package io.kurait.smile.cli;

import io.kurait.smile.s3.SnapshotStore;
import io.kurait.smile.s3.StoreOpener;
import io.kurait.smile.version.VersionCodec;
import io.kurait.smile.version.VersionRewriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "version",
        description = "Inspect every snapshot's recorded version, and (optionally) rewrite the " +
                "version metadata in place to make snapshots restorable on a lower-version cluster.")
public class VersionCommand implements Callable<Integer> {

    @Mixin S3Options s3opts = new S3Options();

    @Option(names = "--to",
            description = "Target version, e.g. 2.19.0. If omitted, runs interactively.")
    String to;

    @Option(names = "--snapshot",
            description = "Restrict rewrite to this snapshot name (repeatable). " +
                    "If omitted: 'all' in interactive mode, or all in --yes mode.",
            arity = "0..*")
    List<String> snapshots = new ArrayList<>();

    @Option(names = "--yes", defaultValue = "false",
            description = "Don't prompt — apply immediately.")
    boolean yes;

    @Option(names = "--show", defaultValue = "false",
            description = "Print the inventory and exit (no edits).")
    boolean show;

    @Override
    public Integer call() throws Exception {
        try (SnapshotStore store = StoreOpener.open(s3opts.repo, s3opts.region, s3opts.endpoint,
                s3opts.accessKey, s3opts.secretKey, s3opts.pathStyle, s3opts.profile)) {

            VersionRewriter rewriter = new VersionRewriter(store);
            VersionRewriter.Inventory inv = rewriter.inventory();
            printTable(inv);

            if (show) return 0;

            // Decide target version
            VersionCodec.Parsed target;
            if (to != null) {
                target = VersionCodec.parse(to);
            } else {
                // Suggest the *minimum* version found as a sensible default
                String suggested = suggestMin(inv);
                String input = prompt(String.format(
                        "Target version (default: %s, blank cancels) > ", suggested));
                if (input.isBlank()) {
                    System.out.println("Cancelled.");
                    return 0;
                }
                target = VersionCodec.parse(input);
            }

            // Decide which snapshots
            List<VersionRewriter.SnapshotEntry> selected = selectSnapshots(inv);
            if (selected.isEmpty()) {
                System.out.println("No snapshots match the selection — nothing to do.");
                return 0;
            }

            VersionRewriter.RewritePlan plan = rewriter.plan(target, selected);

            // Preview
            System.out.println();
            System.out.println("Planned rewrite:");
            System.out.printf("  target version           : %s%n", target.asString());
            System.out.printf("  target version_id (OS)   : %d (=%d ^ 0x%x)%n",
                    target.toOpenSearchId(),
                    target.toLegacyElasticId(),
                    VersionCodec.MASK);
            System.out.printf("  target version_id (ES)   : %d (legacy Elasticsearch encoding)%n",
                    target.toLegacyElasticId());
            System.out.println("  snapshots:");
            for (var e : selected) {
                System.out.printf("    - %-30s uuid=%s  current=%-7s  flavor=%-13s snap-dat=%s%n",
                        e.name(), e.uuid(),
                        e.parsedVersion() != null ? e.parsedVersion().asString() : "?",
                        e.detectedFlavor(),
                        e.snapDatKey() != null ? e.snapDatKey() : "(missing)");
            }
            System.out.println();

            if (!yes) {
                String c = prompt("Apply? [y/N] > ");
                if (!c.trim().equalsIgnoreCase("y")) {
                    System.out.println("Cancelled.");
                    return 0;
                }
            }

            // Apply
            List<String> changes = rewriter.apply(inv, plan);
            System.out.println();
            System.out.println("Applied changes:");
            if (changes.isEmpty()) {
                System.out.println("  (none — repo was already at the target version)");
            } else {
                changes.forEach(c -> System.out.println("  " + c));
            }
            System.out.println();
            System.out.println("Done. Re-register the snapshot repo on the destination cluster, then restore.");
            return 0;
        }
    }

    /* ----------------- helpers ----------------- */

    private List<VersionRewriter.SnapshotEntry> selectSnapshots(VersionRewriter.Inventory inv) throws IOException {
        if (!snapshots.isEmpty()) {
            return inv.snapshots().stream()
                    .filter(e -> snapshots.contains(e.name()))
                    .toList();
        }
        if (yes || to != null) {
            // Non-interactive default: all
            return inv.snapshots();
        }
        // Interactive
        String input = prompt("Snapshots to rewrite (comma-separated names, or 'all') > ");
        if (input.isBlank() || "all".equalsIgnoreCase(input.trim())) {
            return inv.snapshots();
        }
        List<String> wanted = Arrays.stream(input.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return inv.snapshots().stream()
                .filter(e -> wanted.contains(e.name()))
                .toList();
    }

    private static String suggestMin(VersionRewriter.Inventory inv) {
        return inv.snapshots().stream()
                .map(e -> e.parsedVersion())
                .filter(p -> p != null)
                .min((a, b) -> {
                    if (a.major() != b.major()) return Integer.compare(a.major(), b.major());
                    if (a.minor() != b.minor()) return Integer.compare(a.minor(), b.minor());
                    return Integer.compare(a.revision(), b.revision());
                })
                .map(VersionCodec.Parsed::asString)
                .orElse("2.19.0");
    }

    private static void printTable(VersionRewriter.Inventory inv) {
        System.out.println();
        System.out.printf("Repository: gen=%d, manifest=%s%n", inv.generation(), inv.indexNKey());
        System.out.println();
        System.out.printf("  %-32s  %-25s  %-12s  %-14s%n",
                "snapshot", "uuid", "version (str)", "version_id");
        System.out.printf("  %-32s  %-25s  %-12s  %-14s%n",
                "-".repeat(32), "-".repeat(25), "-".repeat(12), "-".repeat(14));
        for (var e : inv.snapshots()) {
            String vidDisplay = e.versionIdFromSnapDat() != null
                    ? String.format("%d (%s)", e.versionIdFromSnapDat(), safeFormat(e.versionIdFromSnapDat()))
                    : "-";
            System.out.printf("  %-32s  %-25s  %-12s  %-14s%n",
                    truncate(e.name(), 32),
                    truncate(e.uuid(), 25),
                    e.versionStringFromIndexN() != null ? e.versionStringFromIndexN() : "-",
                    vidDisplay);
        }
    }

    private static String safeFormat(int id) {
        try { return VersionCodec.fromAnyId(id).asString(); } catch (Exception ex) { return "?"; }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static final BufferedReader STDIN_READER =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private static String prompt(String msg) {
        Console c = System.console();
        if (c != null) {
            String r = c.readLine(msg);
            return r == null ? "" : r;
        }
        // Fallback for piped stdin: ONE shared reader (BufferedReader buffers ahead, so don't make new ones).
        try {
            System.out.print(msg);
            System.out.flush();
            String line = STDIN_READER.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            return "";
        }
    }
}
