package io.kurait.smile;

import io.kurait.smile.cli.CatCommand;
import io.kurait.smile.cli.DecodeFileCommand;
import io.kurait.smile.cli.EditCommand;
import io.kurait.smile.cli.EncodeFileCommand;
import io.kurait.smile.cli.LsCommand;
import io.kurait.smile.cli.PullCommand;
import io.kurait.smile.cli.PushCommand;
import io.kurait.smile.cli.TuiCommand;
import io.kurait.smile.cli.VersionCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "snap-version",
    mixinStandardHelpOptions = true,
    version = "snap-version 0.1.0",
    description = "Inspect and edit Elasticsearch / OpenSearch snapshot repository " +
            "metadata (Smile + Lucene CodecUtil framing) on S3 or local disk \u2014 " +
            "primarily for downgrading a snapshot's recorded version so a lower-version " +
            "cluster can restore it.",
    subcommands = {
        TuiCommand.class,
        VersionCommand.class,
        LsCommand.class,
        CatCommand.class,
        PullCommand.class,
        PushCommand.class,
        EditCommand.class,
        DecodeFileCommand.class,
        EncodeFileCommand.class,
    }
)
public class Main {
    public static void main(String[] args) {
        int rc = new CommandLine(new Main()).execute(args);
        System.exit(rc);
    }
}
