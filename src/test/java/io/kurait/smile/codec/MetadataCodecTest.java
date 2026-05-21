package io.kurait.smile.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataCodecTest {

    @Test
    void roundTripUncompressed() throws Exception {
        ObjectNode tree = new ObjectMapper().createObjectNode();
        tree.put("hello", "world");
        tree.put("number", 42);
        byte[] smile = SmileJson.treeToSmile(tree);

        byte[] framed = MetadataCodec.encode(smile, "metadata", 1, false);
        MetadataCodec.Decoded back = MetadataCodec.decode(framed, "metadata");

        assertThat(back.codecName()).isEqualTo("metadata");
        assertThat(back.version()).isEqualTo(1);
        assertThat(back.compressed()).isFalse();
        assertThat(back.smileBytes()).isEqualTo(smile);

        // Decoded smile -> tree -> matches original
        assertThat(SmileJson.smileToTree(back.smileBytes())).isEqualTo((JsonNode) tree);
    }

    @Test
    void roundTripCompressed() throws Exception {
        ObjectNode tree = new ObjectMapper().createObjectNode();
        // Big-ish payload to make compression actually compress
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append("repeated-").append(i % 7).append(' ');
        tree.put("blob", big.toString());

        byte[] smile = SmileJson.treeToSmile(tree);
        byte[] framed = MetadataCodec.encode(smile, "index-metadata", 1, true);

        // Body must contain DFL\0 right after the header
        // header length = 4 + (1 + "index-metadata".length()) + 4 = 4 + 1 + 14 + 4 = 23
        int headerLen = 4 + 1 + "index-metadata".length() + 4;
        assertThat(framed[headerLen]).isEqualTo((byte) 'D');
        assertThat(framed[headerLen + 1]).isEqualTo((byte) 'F');
        assertThat(framed[headerLen + 2]).isEqualTo((byte) 'L');
        assertThat(framed[headerLen + 3]).isEqualTo((byte) 0);

        MetadataCodec.Decoded back = MetadataCodec.decode(framed, "index-metadata");
        assertThat(back.compressed()).isTrue();
        assertThat(back.smileBytes()).isEqualTo(smile);
    }

    @Test
    void rejectsBadCodecName() throws Exception {
        byte[] framed = MetadataCodec.encode(SmileJson.jsonToSmile("{}"), "metadata", 1, false);
        assertThatThrownBy(() -> MetadataCodec.decode(framed, "snapshot"))
                .hasMessageContaining("codec name mismatch");
    }

    @Test
    void rejectsBadCrc() throws Exception {
        byte[] framed = MetadataCodec.encode(SmileJson.jsonToSmile("{\"a\":1}"), "metadata", 1, false);
        // Flip a body byte to corrupt the CRC
        int headerLen = 4 + 1 + "metadata".length() + 4;
        framed[headerLen] = (byte) (framed[headerLen] ^ 0xFF);

        assertThatThrownBy(() -> MetadataCodec.decode(framed, "metadata"))
                .satisfiesAnyOf(
                    e -> assertThat(e.getMessage()).contains("CRC32 mismatch"),
                    e -> assertThat(e.getMessage()).contains("EOF")
                );
    }

    /**
     * Cross-check: hand-build a frame using the byte layout documented in
     * Lucene's CodecUtil + ES BlobStoreRepository writer, and verify our decoder
     * agrees with our encoder. This is the "would opensearch-migrations be able
     * to read what we just wrote?" smoke test.
     */
    @Test
    void handBuiltFrameMatchesOurEncoder() throws Exception {
        byte[] smile = SmileJson.jsonToSmile("{\"x\":\"y\"}");
        byte[] ours = MetadataCodec.encode(smile, "metadata", 1, false);

        // Hand-build the same frame
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CheckedOutputStream checked = new CheckedOutputStream(sink, new CRC32());
        DataOutputStream out = new DataOutputStream(checked);
        out.writeInt(MetadataCodec.HEADER_MAGIC);
        byte[] codec = "metadata".getBytes(StandardCharsets.UTF_8);
        out.writeByte(codec.length);  // vInt single byte for length<128
        out.write(codec);
        out.writeInt(1); // version
        out.write(smile);
        out.writeInt(MetadataCodec.FOOTER_MAGIC);
        out.writeInt(0);
        long crc = checked.getChecksum().getValue();
        new DataOutputStream(sink).writeLong(crc);
        byte[] reference = sink.toByteArray();

        assertThat(ours).isEqualTo(reference);
    }
}
