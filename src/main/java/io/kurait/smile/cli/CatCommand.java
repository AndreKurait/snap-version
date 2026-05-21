package io.kurait.smile.cli;

import io.kurait.smile.codec.MetadataCodec;
import io.kurait.smile.codec.SmileJson;
import io.kurait.smile.repo.SnapshotRepo;
import io.kurait.smile.s3.SnapshotStore;
import io.kurait.smile.s3.StoreOpener;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "cat", description = "Decode a metadata blob to JSON and print to stdout.")
public class CatCommand implements Callable<Integer> {

    @Mixin S3Options s3opts = new S3Options();

    @Option(names = "--key", required = true,
            description = "Repo-relative key to fetch, e.g. meta-<uuid>.dat or indices/<uuid>/meta-<id>.dat")
    String key;

    @Option(names = "--codec",
            description = "Expected codec name. Auto if omitted: meta-*.dat=metadata, " +
                    "snap-*.dat=snapshot, indices/*/meta-*.dat=index-metadata, indices/*/N/snap-*.dat=snapshot.")
    String codec;

    @Option(names = "--pretty", defaultValue = "true",
            description = "Pretty-print JSON output (default: ${DEFAULT-VALUE}).")
    boolean pretty;

    @Override
    public Integer call() throws Exception {
        try (SnapshotStore store = StoreOpener.open(s3opts.repo, s3opts.region, s3opts.endpoint,
                s3opts.accessKey, s3opts.secretKey, s3opts.pathStyle, s3opts.profile)) {

            byte[] raw = store.get(key);
            String codecName = codec != null ? codec : guessCodec(key);
            MetadataCodec.Decoded decoded = MetadataCodec.decode(raw, codecName);
            System.err.printf("(decoded codec=%s version=%d compressed=%s smile_bytes=%d)%n",
                    decoded.codecName(), decoded.version(), decoded.compressed(), decoded.smileBytes().length);
            System.out.println(SmileJson.smileToJson(decoded.smileBytes(), pretty));
            return 0;
        }
    }

    static String guessCodec(String key) {
        // indices/<id>/meta-<file>.dat   -> index-metadata
        if (key.contains("/meta-") && key.startsWith(SnapshotRepo.INDICES_PREFIX)) {
            return SnapshotRepo.CODEC_INDEX_METADATA;
        }
        // indices/<id>/<shard>/snap-<uuid>.dat -> snapshot (shard)
        if (key.startsWith(SnapshotRepo.INDICES_PREFIX) && key.contains("/snap-")) {
            return SnapshotRepo.CODEC_SNAPSHOT;
        }
        // root meta-<uuid>.dat -> metadata (global)
        if (key.startsWith("meta-")) return SnapshotRepo.CODEC_GLOBAL_METADATA;
        // root snap-<uuid>.dat -> snapshot
        if (key.startsWith("snap-")) return SnapshotRepo.CODEC_SNAPSHOT;
        // unknown — let the codec accept whatever's there
        return null;
    }
}
