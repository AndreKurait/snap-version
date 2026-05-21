package io.kurait.smile.codec;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.fasterxml.jackson.dataformat.smile.SmileGenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Bridge between Smile and JSON. Smile factory configured to match
 * Elasticsearch / OpenSearch's repository writer:
 * <pre>
 *   ENCODE_BINARY_AS_7BIT  = false  (raw 8-bit binary in attached blobs)
 *   AUTO_CLOSE_JSON_CONTENT = false (don't auto-close partial frames)
 *   STRICT_DUPLICATE_DETECTION = false
 *   FAIL_ON_SYMBOL_HASH_OVERFLOW = false (snapshot maps can have huge key sets)
 * </pre>
 *
 * <p>Verified against
 * {@code SnapshotReader/.../version_es_7_10/ElasticsearchConstants_ES_7_10.java}
 * and the equivalent ES 6.8 / 2.4 constants.
 */
public final class SmileJson {

    public static final SmileFactory ES_SMILE_FACTORY;

    static {
        ES_SMILE_FACTORY = SmileFactory.builder()
                .configure(SmileGenerator.Feature.ENCODE_BINARY_AS_7BIT, false)
                .configure(JsonFactory.Feature.FAIL_ON_SYMBOL_HASH_OVERFLOW, false)
                .build();
        ES_SMILE_FACTORY.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
        ES_SMILE_FACTORY.disable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    private SmileJson() {}

    /** Decode Smile bytes into a Jackson tree. */
    public static JsonNode smileToTree(byte[] smile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(ES_SMILE_FACTORY);
        try (ByteArrayInputStream in = new ByteArrayInputStream(smile)) {
            return mapper.readTree(in);
        }
    }

    /** Encode a Jackson tree to Smile bytes (matching ES factory config). */
    public static byte[] treeToSmile(JsonNode tree) throws IOException {
        ObjectMapper mapper = new ObjectMapper(ES_SMILE_FACTORY);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            mapper.writeValue(out, tree);
            return out.toByteArray();
        }
    }

    /** Convert Smile bytes to pretty-printed JSON. */
    public static String smileToJson(byte[] smile, boolean pretty) throws IOException {
        ObjectMapper smileMapper = new ObjectMapper(ES_SMILE_FACTORY);
        ObjectMapper jsonMapper = new ObjectMapper();
        if (pretty) jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
        try (ByteArrayInputStream in = new ByteArrayInputStream(smile)) {
            JsonNode tree = smileMapper.readTree(in);
            return jsonMapper.writeValueAsString(tree);
        }
    }

    /** Convert JSON bytes back to Smile bytes (parsing JSON via plain ObjectMapper). */
    public static byte[] jsonToSmile(byte[] jsonBytes) throws IOException {
        ObjectMapper jsonMapper = new ObjectMapper();
        JsonNode tree = jsonMapper.readTree(jsonBytes);
        return treeToSmile(tree);
    }

    public static byte[] jsonToSmile(String json) throws IOException {
        return jsonToSmile(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
