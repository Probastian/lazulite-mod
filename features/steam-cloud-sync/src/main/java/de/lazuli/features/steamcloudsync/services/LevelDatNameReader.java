package de.lazuli.features.steamcloudsync.services;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * FR7.2 of the cloud-sync-uuid-identity spec: reads a save folder's
 * {@code level.dat}'s top-level {@code Data.LevelName} string tag directly
 * -- a {@link java.util.zip.GZIPInputStream} plus a minimal, hand-rolled NBT
 * walk, with no dependency on Minecraft's own {@code NbtIo}/{@code CompoundTag}
 * classes, matching this feature module's existing Minecraft-type-free
 * design constraint (this class lives in a plain-JVM-testable service
 * package, not a platform module).
 *
 * <p>Never throws: any I/O failure, malformed gzip, malformed NBT, or a
 * missing {@code Data}/{@code LevelName} tag returns the caller-supplied
 * {@code fallback} instead -- mirroring
 * {@code WorldConflictResolutionHook.LevelDatBatch::unreadable}'s existing
 * "readable-or-not" precedent elsewhere in this codebase.
 *
 * <p>Usage example:
 * <pre>{@code
 * String displayName = LevelDatNameReader.readLevelName(worldFolder, worldFolder.getFileName().toString());
 * }</pre>
 */
public final class LevelDatNameReader {

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    private LevelDatNameReader() {
    }

    /**
     * @param levelDatFolder the world's on-disk save folder (containing
     *                       {@code level.dat} directly)
     * @param fallback       returned verbatim if {@code level.dat} is
     *                       missing, unreadable, malformed, or has no
     *                       {@code Data.LevelName} string tag
     * @return the real {@code LevelName}, or {@code fallback}
     */
    public static String readLevelName(Path levelDatFolder, String fallback) {
        Path levelDatFile = levelDatFolder.resolve("level.dat");
        if (!Files.isRegularFile(levelDatFile)) {
            return fallback;
        }
        try (InputStream in = Files.newInputStream(levelDatFile);
                GZIPInputStream gzip = new GZIPInputStream(in);
                DataInputStream data = new DataInputStream(gzip)) {
            int rootType = data.readUnsignedByte();
            if (rootType != TAG_COMPOUND) {
                return fallback;
            }
            readUtf(data); // root tag's own name, discarded
            String levelName = readCompoundForLevelName(data);
            return levelName != null ? levelName : fallback;
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Walks a compound tag's own payload looking for a nested {@code Data}
     * compound at this level, and within it a {@code LevelName} string tag.
     * Every other field, at any depth, is generically skipped without being
     * interpreted.
     */
    private static String readCompoundForLevelName(DataInputStream data) throws IOException {
        while (true) {
            int type = data.readUnsignedByte();
            if (type == TAG_END) {
                return null;
            }
            String name = readUtf(data);
            if (type == TAG_COMPOUND && "Data".equals(name)) {
                return readDataCompoundForLevelName(data);
            }
            skipPayload(data, type);
        }
    }

    /** Walks the {@code Data} compound's own payload looking for {@code LevelName}. */
    private static String readDataCompoundForLevelName(DataInputStream data) throws IOException {
        String levelName = null;
        while (true) {
            int type = data.readUnsignedByte();
            if (type == TAG_END) {
                return levelName;
            }
            String name = readUtf(data);
            if (type == TAG_STRING && "LevelName".equals(name)) {
                levelName = readUtf(data);
            } else {
                skipPayload(data, type);
            }
        }
    }

    /** Skips one tag payload of {@code type}, without interpreting its contents. */
    private static void skipPayload(DataInputStream data, int type) throws IOException {
        switch (type) {
            case TAG_BYTE -> data.skipBytes(1);
            case TAG_SHORT -> data.skipBytes(2);
            case TAG_INT, TAG_FLOAT -> data.skipBytes(4);
            case TAG_LONG, TAG_DOUBLE -> data.skipBytes(8);
            case TAG_BYTE_ARRAY -> skipFully(data, data.readInt());
            case TAG_STRING -> readUtf(data);
            case TAG_LIST -> skipList(data);
            case TAG_COMPOUND -> skipCompound(data);
            case TAG_INT_ARRAY -> skipFully(data, data.readInt() * 4L);
            case TAG_LONG_ARRAY -> skipFully(data, data.readInt() * 8L);
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static void skipList(DataInputStream data) throws IOException {
        int elementType = data.readUnsignedByte();
        int count = data.readInt();
        if (elementType == TAG_END) {
            return;
        }
        for (int i = 0; i < count; i++) {
            skipPayload(data, elementType);
        }
    }

    private static void skipCompound(DataInputStream data) throws IOException {
        while (true) {
            int type = data.readUnsignedByte();
            if (type == TAG_END) {
                return;
            }
            readUtf(data);
            skipPayload(data, type);
        }
    }

    private static void skipFully(DataInputStream data, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = data.skip(remaining);
            if (skipped <= 0) {
                // DataInputStream.skip can under-skip; fall back to reading/discarding.
                if (data.read() < 0) {
                    throw new IOException("Unexpected end of stream while skipping " + bytes + " bytes");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    /** NBT's own modified-UTF-8 string encoding: an unsigned short length prefix, then {@code readUTF}-compatible bytes. */
    private static String readUtf(DataInputStream data) throws IOException {
        return data.readUTF();
    }
}
