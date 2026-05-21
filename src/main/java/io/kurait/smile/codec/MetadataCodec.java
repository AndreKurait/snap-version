package io.kurait.smile.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Decodes and re-encodes the Lucene/ES "CodecUtil" framing used by Elasticsearch /
 * OpenSearch repository blobs (meta-*.dat, snap-*.dat, indices/&lt;id&gt;/meta-*.dat).
 *
 * <h3>Frame layout</h3>
 *
 * <pre>
 *   +--------------------------------------+
 *   | header_magic = 0x3FD76C17 (4 bytes)  |
 *   +--------------------------------------+
 *   | UTF8 string: codec name              |   ("metadata" | "index-metadata" | "snapshot")
 *   +--------------------------------------+
 *   | int32 BE: version                    |   (we write/check version=1)
 *   +--------------------------------------+
 *   | body (raw Smile, OR "DFL\0" + raw    |
 *   |       DEFLATE that decompresses to   |
 *   |       Smile)                         |
 *   +--------------------------------------+
 *   | footer_magic = 0xC02893E8 (4 bytes)  |
 *   | int32 BE: 0                          |
 *   | int64 BE: crc32 of all bytes before  |
 *   |          the footer's first byte     |
 *   +--------------------------------------+
 * </pre>
 *
 * <p>This mirrors {@code shadow.lucene9.org.apache.lucene.codecs.CodecUtil#writeHeader}
 * and {@code writeFooter}, used by ES's {@code BlobStoreRepository} and consumed by
 * opensearch-migrations'
 * {@link "RfsCommon/.../SnapshotMetadataLoader#processMetadataBytes"}.
 */
public final class MetadataCodec {

    public static final int HEADER_MAGIC = 0x3FD76C17;
    public static final int FOOTER_MAGIC = 0xC02893E8; // ~HEADER_MAGIC

    /** "DFL\0" — OpenSearch DEFLATE-frame marker placed immediately after the header. */
    public static final byte[] DFL_HEADER = {'D', 'F', 'L', 0};

    private MetadataCodec() {}

    /* ------------------------------------------------------------------ */
    /*  DECODE                                                             */
    /* ------------------------------------------------------------------ */

    /** Result of decoding a metadata blob: the Smile bytes plus original framing details. */
    public record Decoded(
            String codecName,
            int version,
            boolean compressed,
            byte[] smileBytes
    ) {}

    /**
     * Parse a complete metadata blob and return the decoded Smile body, plus the framing
     * details we need to re-emit a byte-identical (modulo CRC) blob on encode.
     *
     * @param raw      complete blob bytes as fetched from S3
     * @param expected expected codec name ("metadata", "index-metadata", "snapshot"); if null,
     *                 any codec name in the header is accepted.
     */
    public static Decoded decode(byte[] raw, String expected) throws IOException {
        if (raw.length < 8 + 4) {
            throw new IOException("blob too short: " + raw.length + " bytes");
        }

        // 1. Header
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int magic = in.readInt();
        if (magic != HEADER_MAGIC) {
            throw new IOException(String.format(
                    "header magic mismatch: expected 0x%08X but got 0x%08X", HEADER_MAGIC, magic));
        }
        String codecName = readUtfString(in);
        int version = in.readInt();
        if (expected != null && !expected.equals(codecName)) {
            throw new IOException("codec name mismatch: expected '" + expected + "' got '" + codecName + "'");
        }

        // The header consumed (raw.length - in.available()) bytes; remaining = body + footer.
        int headerBytes = raw.length - in.available();
        int footerStart = raw.length - 16; // 4 magic + 4 zero + 8 crc
        if (footerStart < headerBytes) {
            throw new IOException("blob shorter than header+footer");
        }

        // 2. Footer & CRC validation
        verifyFooter(raw, footerStart);

        byte[] body = Arrays.copyOfRange(raw, headerBytes, footerStart);

        // 3. Body: either DFL\0 + raw deflate, or raw smile
        boolean compressed = startsWithDfl(body);
        byte[] smile = compressed ? inflateRawDeflate(body, DFL_HEADER.length) : body;

        return new Decoded(codecName, version, compressed, smile);
    }

    private static void verifyFooter(byte[] raw, int footerStart) throws IOException {
        DataInputStream f = new DataInputStream(new ByteArrayInputStream(raw, footerStart, 16));
        int fMagic = f.readInt();
        int fZero = f.readInt();
        long fCrc = f.readLong();
        if (fMagic != FOOTER_MAGIC) {
            throw new IOException(String.format(
                    "footer magic mismatch: expected 0x%08X but got 0x%08X", FOOTER_MAGIC, fMagic));
        }
        if (fZero != 0) {
            throw new IOException("footer reserved-zero is non-zero: " + fZero);
        }
        // Per Lucene CodecUtil#writeFooter: the CRC is computed over EVERY byte written
        // before the CRC value itself — that includes header + body + footer-magic(4) + zero(4).
        // i.e. crc = crc32(raw[0 .. footerStart + 8)).
        CRC32 crc = new CRC32();
        crc.update(raw, 0, footerStart + 8);
        long actual = crc.getValue();
        if (actual != fCrc) {
            throw new IOException("footer CRC32 mismatch: expected=" + fCrc + " actual=" + actual);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  ENCODE                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Re-emit a metadata blob with the supplied Smile body, preserving the original
     * codec name, version, and compression mode. CRC32 is recomputed.
     */
    public static byte[] encode(byte[] smileBody, Decoded original) throws IOException {
        return encode(smileBody, original.codecName(), original.version(), original.compressed());
    }

    public static byte[] encode(byte[] smileBody, String codecName, int version, boolean compress)
            throws IOException {
        // We compute the CRC progressively so we never have to walk the buffer twice.
        ByteArrayOutputStream sink = new ByteArrayOutputStream(smileBody.length + 64);
        CheckedOutputStream checked = new CheckedOutputStream(sink, new CRC32());
        DataOutputStream out = new DataOutputStream(checked);

        // 1. Header
        out.writeInt(HEADER_MAGIC);
        writeUtfString(out, codecName);
        out.writeInt(version);

        // 2. Body
        if (compress) {
            out.write(DFL_HEADER);
            // ES uses raw DEFLATE (no zlib wrapper), DEFAULT_COMPRESSION, default strategy.
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(out, deflater, /*syncFlush*/ false) {
                @Override public void close() throws IOException { finish(); /* keep `out` open */ }
            }) {
                dos.write(smileBody);
            } finally {
                deflater.end();
            }
        } else {
            out.write(smileBody);
        }

        // 3. Footer: write magic+zero into the CRC stream, THEN write the CRC value
        //    (the CRC covers header + body + magic + zero, but not itself).
        out.writeInt(FOOTER_MAGIC);
        out.writeInt(0);
        long crc = checked.getChecksum().getValue();
        // CRC value is written raw (without updating the checksum, but it doesn't matter
        // since we already captured the value).
        DataOutputStream raw = new DataOutputStream(sink);
        raw.writeLong(crc);

        return sink.toByteArray();
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static boolean startsWithDfl(byte[] body) {
        if (body.length < DFL_HEADER.length) return false;
        for (int i = 0; i < DFL_HEADER.length; i++) {
            if (body[i] != DFL_HEADER[i]) return false;
        }
        return true;
    }

    private static byte[] inflateRawDeflate(byte[] body, int offset) throws IOException {
        Inflater inflater = new Inflater(true); // raw DEFLATE
        try (ByteArrayInputStream bin = new ByteArrayInputStream(body, offset, body.length - offset);
             InflaterInputStream iis = new InflaterInputStream(bin, inflater);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = iis.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /**
     * Reads the same UTF8 length-prefixed string format that
     * {@code DataOutput.writeUTF / DataInput.readUTF} uses (Lucene's
     * {@code DataOutput.writeString} writes a vInt length + UTF-8 bytes — but the
     * <em>codec header</em> writer in CodecUtil uses {@code DataOutput.writeString}
     * which itself writes a vInt length followed by raw UTF-8 bytes).
     */
    static String readUtfString(DataInputStream in) throws IOException {
        int len = readVInt(in);
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    static void writeUtfString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVInt(out, bytes.length);
        out.write(bytes);
    }

    /** Lucene's variable-length unsigned int (LEB128-ish, 7 bits per byte, MSB=continuation). */
    static int readVInt(DataInputStream in) throws IOException {
        byte b = in.readByte();
        if (b >= 0) return b;
        int i = b & 0x7F;
        b = in.readByte();
        i |= (b & 0x7F) << 7;
        if (b >= 0) return i;
        b = in.readByte();
        i |= (b & 0x7F) << 14;
        if (b >= 0) return i;
        b = in.readByte();
        i |= (b & 0x7F) << 21;
        if (b >= 0) return i;
        b = in.readByte();
        // 5th byte must have only the lower 4 bits set
        i |= (b & 0x0F) << 28;
        if ((b & 0xF0) != 0) {
            throw new IOException("invalid vInt continuation byte: 0x" + Integer.toHexString(b & 0xFF));
        }
        return i;
    }

    static void writeVInt(DataOutputStream out, int i) throws IOException {
        while ((i & ~0x7F) != 0) {
            out.writeByte((byte) ((i & 0x7F) | 0x80));
            i >>>= 7;
        }
        out.writeByte((byte) i);
    }
}
