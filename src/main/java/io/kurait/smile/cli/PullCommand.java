package io.kurait.smile.cli;

import io.kurait.smile.codec.MetadataCodec;
import io.kurait.smile.codec.SmileJson;
import io.kurait.smile.s3.SnapshotStore;
import io.kurait.smile.s3.StoreOpener;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "pull",
        description = "Download a metadata blob, write the JSON view + a sidecar with codec info " +
                "so you can edit JSON locally and `push` it back.")
public class PullCommand implements Callable<Integer> {

    @Mixin S3Options s3opts = new S3Options();

    @Option(names = "--key", required = true, description = "Repo-relative key to pull.")
    String key;

    @Option(names = "--codec", description = "Expected codec name (auto-detected by default).")
    String codec;

    @Option(names = "--out-dir", required = true,
            description = "Local directory where {file}.json + {file}.sidecar.json are written.")
    Path outDir;

    @Override
    public Integer call() throws Exception {
        Files.createDirectories(outDir);
        try (SnapshotStore store = StoreOpener.open(s3opts.repo, s3opts.region, s3opts.endpoint,
                s3opts.accessKey, s3opts.secretKey, s3opts.pathStyle, s3opts.profile)) {

            byte[] raw = store.get(key);
            String codecName = codec != null ? codec : CatCommand.guessCodec(key);
            MetadataCodec.Decoded decoded = MetadataCodec.decode(raw, codecName);

            String filename = sanitize(key);
            Path jsonOut = outDir.resolve(filename + ".json");
            Path sidecarOut = outDir.resolve(filename + ".sidecar.json");

            Files.writeString(jsonOut, SmileJson.smileToJson(decoded.smileBytes(), true));
            Files.writeString(sidecarOut, sidecarJson(key, decoded));

            System.out.printf("Wrote %s%n", jsonOut);
            System.out.printf("Wrote %s%n", sidecarOut);
            return 0;
        }
    }

    static String sanitize(String key) {
        return key.replace('/', '_');
    }

    static String sidecarJson(String key, MetadataCodec.Decoded d) throws IOException {
        // Hand-rolled tiny JSON to keep this file dependency-light.
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"key\": ").append(jsonString(key)).append(",\n");
        sb.append("  \"codecName\": ").append(jsonString(d.codecName())).append(",\n");
        sb.append("  \"version\": ").append(d.version()).append(",\n");
        sb.append("  \"compressed\": ").append(d.compressed()).append(",\n");
        sb.append("  \"smileBytesLength\": ").append(d.smileBytes().length).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
