package io.kurait.smile.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kurait.smile.codec.MetadataCodec;
import io.kurait.smile.codec.SmileJson;
import io.kurait.smile.s3.SnapshotStore;
import io.kurait.smile.s3.StoreOpener;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "push",
        description = "Re-encode an edited JSON file back to Smile + codec frame and upload.")
public class PushCommand implements Callable<Integer> {

    @Mixin S3Options s3opts = new S3Options();

    @Option(names = "--json", required = true, description = "Edited JSON file from a prior `pull`.")
    Path jsonFile;

    @Option(names = "--sidecar", required = true,
            description = "Sidecar JSON (codec name / version / compressed) from a prior `pull`.")
    Path sidecarFile;

    @Option(names = "--key", description = "Override the destination repo-relative key " +
            "(default: read from sidecar.key).")
    String overrideKey;

    @Option(names = "--dry-run", defaultValue = "false",
            description = "Print the produced byte length and bail out without uploading.")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode sidecar = m.readTree(Files.readAllBytes(sidecarFile));
        String key = overrideKey != null ? overrideKey : sidecar.get("key").asText();
        String codecName = sidecar.get("codecName").asText();
        int version = sidecar.get("version").asInt();
        boolean compressed = sidecar.get("compressed").asBoolean();

        byte[] jsonBytes = Files.readAllBytes(jsonFile);
        byte[] smile = SmileJson.jsonToSmile(jsonBytes);
        byte[] framed = MetadataCodec.encode(smile, codecName, version, compressed);

        // Sanity check: round-trip our own output before sending.
        MetadataCodec.Decoded back = MetadataCodec.decode(framed, codecName);
        if (back.smileBytes().length != smile.length) {
            System.err.printf("WARNING: smile bytes length differs after round-trip: orig=%d roundtrip=%d%n",
                    smile.length, back.smileBytes().length);
        }

        System.out.printf("Encoded %d JSON bytes -> %d Smile bytes -> %d framed bytes (codec=%s, version=%d, compressed=%s, key=%s)%n",
                jsonBytes.length, smile.length, framed.length, codecName, version, compressed, key);

        if (dryRun) {
            System.out.println("--dry-run set; not uploading.");
            return 0;
        }

        try (SnapshotStore store = StoreOpener.open(s3opts.repo, s3opts.region, s3opts.endpoint,
                s3opts.accessKey, s3opts.secretKey, s3opts.pathStyle, s3opts.profile)) {
            store.put(key, framed);
            System.out.println("Uploaded.");
            return 0;
        }
    }
}
