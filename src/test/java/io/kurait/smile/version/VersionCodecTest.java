package io.kurait.smile.version;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionCodecTest {

    @Test
    void encodingMatchesKnownOpenSearchIds() {
        // Verified against running 2.19.4 cluster + Version.java constants
        assertThat(VersionCodec.parse("2.19.0").toOpenSearchId()).isEqualTo(136_407_827);
        assertThat(VersionCodec.parse("2.19.4").toOpenSearchId()).isEqualTo(136_408_227);
        assertThat(VersionCodec.parse("2.15.0").toOpenSearchId()).isEqualTo(136_367_827);
        assertThat(VersionCodec.parse("2.0.0").toOpenSearchId()).isEqualTo(136_217_827);
        assertThat(VersionCodec.parse("3.0.0").toOpenSearchId()).isEqualTo(137_217_827);
    }

    @Test
    void roundTripOpenSearch() {
        for (String v : new String[]{"2.19.0", "2.19.4", "2.15.0", "2.0.0", "3.0.0", "1.3.18"}) {
            VersionCodec.Parsed p = VersionCodec.parse(v);
            int id = p.toOpenSearchId();
            VersionCodec.Parsed back = VersionCodec.fromOpenSearchId(id);
            assertThat(back.asString()).as(v).isEqualTo(v);
        }
    }

    @Test
    void roundTripLegacyElasticsearch() {
        // Legacy ES IDs do NOT have the mask flipped.
        VersionCodec.Parsed p = VersionCodec.parse("7.10.2");
        int id = p.toLegacyElasticId();
        assertThat(id).isEqualTo(7_100_299);
        assertThat(VersionCodec.fromLegacyElasticId(id).asString()).isEqualTo("7.10.2");
    }

    @Test
    void encodingMatchesKnownElasticsearchIds() {
        // Verified against elastic/elasticsearch's Version.java constants.
        assertThat(VersionCodec.parse("7.10.0").toLegacyElasticId()).isEqualTo(7_100_099);
        assertThat(VersionCodec.parse("7.10.1").toLegacyElasticId()).isEqualTo(7_100_199);
        assertThat(VersionCodec.parse("7.10.2").toLegacyElasticId()).isEqualTo(7_100_299);
        assertThat(VersionCodec.parse("7.17.0").toLegacyElasticId()).isEqualTo(7_170_099);
        assertThat(VersionCodec.parse("7.17.28").toLegacyElasticId()).isEqualTo(7_172_899);
    }

    @Test
    void detectFlavorFromVersionId() {
        // OpenSearch IDs have bit 27 set
        assertThat(VersionCodec.detectFlavor(VersionCodec.parse("2.19.0").toOpenSearchId()))
                .isEqualTo(VersionCodec.Flavor.OPENSEARCH);
        // Legacy ES IDs do not
        assertThat(VersionCodec.detectFlavor(VersionCodec.parse("7.17.28").toLegacyElasticId()))
                .isEqualTo(VersionCodec.Flavor.ELASTICSEARCH);
        // Edge: ES 7.10.2 still ES (no mask)
        assertThat(VersionCodec.detectFlavor(7_100_299))
                .isEqualTo(VersionCodec.Flavor.ELASTICSEARCH);
    }

    @Test
    void parsedToIdHonorsFlavor() {
        VersionCodec.Parsed v = VersionCodec.parse("7.17.0");
        assertThat(v.toId(VersionCodec.Flavor.ELASTICSEARCH)).isEqualTo(7_170_099);
        assertThat(v.toId(VersionCodec.Flavor.OPENSEARCH)).isEqualTo(7_170_099 ^ VersionCodec.MASK);
    }

    @Test
    void fromAnyIdAutoSelectsFlavor() {
        // OS id has bit 27 set
        int osId = VersionCodec.parse("2.19.4").toOpenSearchId();
        assertThat(VersionCodec.fromAnyId(osId).asString()).isEqualTo("2.19.4");

        // Legacy ES id without mask
        int esId = VersionCodec.parse("7.10.2").toLegacyElasticId();
        assertThat(VersionCodec.fromAnyId(esId).asString()).isEqualTo("7.10.2");
    }

    @Test
    void rejectsBadStrings() {
        assertThatThrownBy(() -> VersionCodec.parse("blah")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionCodec.parse("2.19")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionCodec.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionCodec.parse("100.0.0")).isInstanceOf(IllegalArgumentException.class);
    }
}
