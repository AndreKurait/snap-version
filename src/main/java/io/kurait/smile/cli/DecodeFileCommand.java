package io.kurait.smile.cli;

import io.kurait.smile.codec.MetadataCodec;
import io.kurait.smile.codec.SmileJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "decode-file",
        description = "Decode a local metadata file (no S3) — useful for offline analysis.")
public class DecodeFileCommand implements Callable<Integer> {

    @Option(names = "--in", required = true, description = "Local metadata blob to decode.")
    Path in;

    @Option(names = "--out", description = "Where to write the JSON (default: stdout).")
    Path out;

    @Option(names = "--codec", description = "Expected codec name (otherwise accepts any).")
    String codec;

    @Option(names = "--pretty", defaultValue = "true") boolean pretty;

    @Override
    public Integer call() throws Exception {
        byte[] raw = Files.readAllBytes(in);
        MetadataCodec.Decoded d = MetadataCodec.decode(raw, codec);
        System.err.printf("(decoded codec=%s version=%d compressed=%s smile_bytes=%d)%n",
                d.codecName(), d.version(), d.compressed(), d.smileBytes().length);
        String json = SmileJson.smileToJson(d.smileBytes(), pretty);
        if (out == null) {
            System.out.println(json);
        } else {
            Files.writeString(out, json);
            System.out.println("Wrote " + out);
        }
        return 0;
    }
}
