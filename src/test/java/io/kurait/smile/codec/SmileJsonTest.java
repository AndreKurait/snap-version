package io.kurait.smile.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmileJsonTest {

    @Test
    void roundTrip() throws Exception {
        String json = "{\"name\":\"my-index\",\"shards\":3,\"settings\":{\"compress\":true}}";
        byte[] smile = SmileJson.jsonToSmile(json);
        // Smile magic ":)\n"
        assertThat(smile[0]).isEqualTo((byte) ':');
        assertThat(smile[1]).isEqualTo((byte) ')');
        assertThat(smile[2]).isEqualTo((byte) '\n');

        JsonNode tree = SmileJson.smileToTree(smile);
        assertThat(tree.get("name").asText()).isEqualTo("my-index");
        assertThat(tree.get("shards").asInt()).isEqualTo(3);

        String back = SmileJson.smileToJson(smile, false);
        // Re-parse to compare semantically (formatting may differ)
        assertThat(SmileJson.smileToTree(SmileJson.jsonToSmile(back))).isEqualTo(tree);
    }
}
