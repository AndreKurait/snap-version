package io.kurait.smile.cli;

import io.kurait.smile.codec.MetadataCodec;
import io.kurait.smile.codec.SmileJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "encode-file",
        description = "Encode a local JSON file back to a metadata blob (no S3).")
public class EncodeFileCommand implements Callable<Integer> {

    @Option(names = "--json", required = true, description = "Local JSON file to encode.")
    Path jsonFile;

    @Option(names = "--out", required = true, description = "Output path for the framed blob.")
    Path outFile;

    @Option(names = "--codec", required = true,
            description = "Codec name to embed in the header (metadata, index-metadata, snapshot).")
    String codec;

    @Option(names = "--version", defaultValue = "1", description = "Codec version (default: 1).")
    int version;

    @Option(names = "--compress", defaultValue = "true",
            description = "Wrap the Smile body in DFL\\0 + raw DEFLATE (default: ${DEFAULT-VALUE}).")
    boolean compress;

    @Override
    public Integer call() throws Exception {
        byte[] json = Files.readAllBytes(jsonFile);
        byte[] smile = SmileJson.jsonToSmile(json);
        byte[] framed = MetadataCodec.encode(smile, codec, version, compress);
        Files.write(outFile, framed);
        System.out.printf("Wrote %s (%d bytes; smile=%d; compressed=%s; codec=%s; version=%d)%n",
                outFile, framed.length, smile.length, compress, codec, version);
        return 0;
    }
}
