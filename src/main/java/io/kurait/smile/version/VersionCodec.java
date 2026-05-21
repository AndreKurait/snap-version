package io.kurait.smile.version;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes / decodes OpenSearch (and legacy Elasticsearch) version IDs.
 *
 * <h3>OpenSearch encoding</h3>
 * <pre>
 *   versionId = ((major * 1_000_000) + (minor * 10_000) + (revision * 100) + 99) ^ 0x08000000
 * </pre>
 * The XOR with {@code MASK = 0x08000000} flips bit 27 to disambiguate
 * OpenSearch from legacy Elasticsearch versions on the wire.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code 2.19.0} → {@code 136_407_827} (= {@code 2_190_099 ^ 0x08000000})</li>
 *   <li>{@code 2.19.4} → {@code 136_408_227}</li>
 *   <li>{@code 2.15.0} → {@code 136_367_827}</li>
 * </ul>
 *
 * <h3>Legacy Elasticsearch encoding</h3>
 * Same MMmmrrbb decimal, NO mask. e.g. ES 7.10.2 → {@code 7_100_299}.
 *
 * <p>Source of truth: {@code org.opensearch.Version} (libs/core).
 */
public final class VersionCodec {

    public static final int MASK = 0x08000000; // 134_217_728

    private static final Pattern VERSION_RE = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    private VersionCodec() {}

    /** Parsed OS-style version. */
    public record Parsed(int major, int minor, int revision) {
        public String asString() {
            return major + "." + minor + "." + revision;
        }
        public int toOpenSearchId() {
            return ((major * 1_000_000) + (minor * 10_000) + (revision * 100) + 99) ^ MASK;
        }
        public int toLegacyElasticId() {
            return (major * 1_000_000) + (minor * 10_000) + (revision * 100) + 99;
        }
    }

    /** Parse a version string like {@code "2.19.4"}. */
    public static Parsed parse(String s) {
        if (s == null) throw new IllegalArgumentException("null version");
        Matcher m = VERSION_RE.matcher(s.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a major.minor.revision string: " + s);
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int revision = Integer.parseInt(m.group(3));
        if (major < 0 || major > 99 || minor < 0 || minor > 99 || revision < 0 || revision > 99) {
            throw new IllegalArgumentException("version components out of range [0..99]: " + s);
        }
        return new Parsed(major, minor, revision);
    }

    /** Decode an OpenSearch version_id back to a Parsed (XOR's the mask off first). */
    public static Parsed fromOpenSearchId(int id) {
        int unmasked = id ^ MASK;
        return decodeUnmasked(unmasked, "OpenSearch", id);
    }

    /** Decode a legacy ES version_id (no mask). */
    public static Parsed fromLegacyElasticId(int id) {
        return decodeUnmasked(id, "Elasticsearch", id);
    }

    /**
     * Try OpenSearch decoding first; if it produces nonsense (e.g. major outside 1..99),
     * fall back to the legacy ES form. Useful when reading a snapshot whose origin is unclear.
     */
    public static Parsed fromAnyId(int id) {
        // OpenSearch ids have bit 27 set (XOR with 0x08000000).
        if ((id & MASK) != 0) {
            return fromOpenSearchId(id);
        }
        return fromLegacyElasticId(id);
    }

    private static Parsed decodeUnmasked(int unmasked, String flavor, int original) {
        int build    = unmasked % 100;          unmasked /= 100;
        int revision = unmasked % 100;          unmasked /= 100;
        int minor    = unmasked % 100;          unmasked /= 100;
        int major    = unmasked;
        if (build != 99) {
            throw new IllegalArgumentException(
                    String.format("%s id %d has non-release build byte (%d != 99) — pre-release?",
                            flavor, original, build));
        }
        if (major < 1 || major > 99 || minor < 0 || minor > 99 || revision < 0 || revision > 99) {
            throw new IllegalArgumentException(
                    String.format("%s id %d decodes to nonsensical version %d.%d.%d",
                            flavor, original, major, minor, revision));
        }
        return new Parsed(major, minor, revision);
    }
}
