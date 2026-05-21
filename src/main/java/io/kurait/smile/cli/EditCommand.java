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
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "edit",
        description = "Pull a metadata blob, open it in $EDITOR (or --editor) as JSON, " +
                "then re-encode and push back. Convenience wrapper around pull+push.")
public class EditCommand implements Callable<Integer> {

    @Mixin S3Options s3opts = new S3Options();

    @Option(names = "--key", required = true, description = "Repo-relative key to edit.")
    String key;

    @Option(names = "--codec", description = "Expected codec name (auto-detected by default).")
    String codec;

    @Option(names = "--editor", description = "Editor command to invoke (default: $EDITOR or `vi`).")
    String editor;

    @Option(names = "--no-upload", defaultValue = "false",
            description = "Decode + edit only; don't upload changes.")
    boolean noUpload;

    @Override
    public Integer call() throws Exception {
        try (SnapshotStore store = StoreOpener.open(s3opts.repo, s3opts.region, s3opts.endpoint,
                s3opts.accessKey, s3opts.secretKey, s3opts.pathStyle, s3opts.profile)) {

            byte[] raw = store.get(key);
            String codecName = codec != null ? codec : CatCommand.guessCodec(key);
            MetadataCodec.Decoded decoded = MetadataCodec.decode(raw, codecName);

            Path tmp = Files.createTempFile("smile-edit-", ".json");
            Files.writeString(tmp, SmileJson.smileToJson(decoded.smileBytes(), true));
            System.err.println("Wrote " + tmp + " — opening editor...");

            int rc = invokeEditor(tmp);
            if (rc != 0) {
                System.err.println("Editor exited non-zero (" + rc + "); aborting.");
                return rc;
            }

            byte[] editedJson = Files.readAllBytes(tmp);
            byte[] smile = SmileJson.jsonToSmile(editedJson);
            byte[] framed = MetadataCodec.encode(smile, decoded);
            System.err.printf("Re-encoded: json=%d -> smile=%d -> framed=%d (compressed=%s)%n",
                    editedJson.length, smile.length, framed.length, decoded.compressed());

            if (noUpload) {
                Path out = Path.of(tmp.toString() + ".framed");
                Files.write(out, framed);
                System.out.println("--no-upload set; framed bytes saved to " + out);
                return 0;
            }
            store.put(key, framed);
            System.out.println("Uploaded " + framed.length + " bytes to " + store.describe() + "/" + key);
            return 0;
        }
    }

    private int invokeEditor(Path file) throws IOException, InterruptedException {
        String cmd = editor != null ? editor : System.getenv().getOrDefault("EDITOR", "vi");
        ProcessBuilder pb = new ProcessBuilder(List.of(cmd, file.toString()));
        pb.inheritIO();
        return pb.start().waitFor();
    }
}
