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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Command(
    name = "snap-version",
    mixinStandardHelpOptions = true,
    versionProvider = Main.BuildVersion.class,
    description = "Inspect and edit Elasticsearch / OpenSearch snapshot repository " +
            "metadata (Smile + Lucene CodecUtil framing) on S3 or local disk \u2014 " +
            "for controlled recovery experiments on offline copies. Editing version " +
            "metadata does not convert index data or establish restore compatibility.",
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
    /** Read the version packaged by Gradle, including release -Pversion overrides. */
    public static class BuildVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() throws IOException {
            Properties properties = new Properties();
            try (InputStream resource = Main.class.getResourceAsStream("/snap-version.properties")) {
                if (resource == null) throw new IOException("missing packaged version resource");
                properties.load(resource);
            }
            return new String[]{"snap-version " + properties.getProperty("version")};
        }
    }

    public static void main(String[] args) {
        int rc = new CommandLine(new Main()).execute(args);
        System.exit(rc);
    }
}
