package io.kurait.smile.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.kurait.smile.repo.SnapshotRepo;
import io.kurait.smile.s3.SnapshotStore;
import io.kurait.smile.s3.StoreOpener;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

@Command(name = "ls", description = "List snapshots and indices in the repository (parses index-N).")
public class LsCommand implements Callable<Integer> {

    @Mixin S3Options s3opts = new S3Options();

    @Override
    public Integer call() throws Exception {
        try (SnapshotStore store = StoreOpener.open(s3opts.repo, s3opts.region, s3opts.endpoint,
                s3opts.accessKey, s3opts.secretKey, s3opts.pathStyle, s3opts.profile)) {

            SnapshotRepo repo = new SnapshotRepo(store);
            long latest = repo.readIndexLatest();
            if (latest < 0) {
                latest = repo.findHighestIndexGeneration();
                System.err.println("(index.latest missing — falling back to scanned generation: " + latest + ")");
            }
            if (latest < 0) {
                System.err.println("ERROR: no index-N file found in repo");
                return 2;
            }
            System.out.println("Repository: " + s3opts.repo);
            System.out.println("Generation (index.latest): " + latest);

            JsonNode manifest = repo.readManifest(latest);

            System.out.println();
            System.out.println("Snapshots:");
            for (var s : repo.listSnapshots(manifest)) {
                System.out.printf("  %-32s  uuid=%s  version=%s%n", s.name(), s.uuid(), s.version());
            }
            System.out.println();
            System.out.println("Indices:");
            for (var i : repo.listIndices(manifest)) {
                System.out.printf("  %-32s  uuid=%s%n", i.name(), i.uuid());
            }
            return 0;
        }
    }
}
