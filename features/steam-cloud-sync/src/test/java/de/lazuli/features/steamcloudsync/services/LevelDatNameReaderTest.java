package de.lazuli.features.steamcloudsync.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class LevelDatNameReaderTest {

    private static final int TAG_END = 0;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_STRING = 8;
    private static final int TAG_INT = 3;

    @Test
    void readsLevelNameFromValidLevelDat(@TempDir Path tempDir) throws IOException {
        writeLevelDat(tempDir, "My World");

        String result = LevelDatNameReader.readLevelName(tempDir, "fallback");

        assertThat(result).isEqualTo("My World");
    }

    @Test
    void missingLevelDatReturnsFallback(@TempDir Path tempDir) {
        String result = LevelDatNameReader.readLevelName(tempDir, "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void missingDataTagReturnsFallback(@TempDir Path tempDir) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(raw); DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");
            // No "Data" compound at all.
            out.writeByte(TAG_END);
        }
        Files.write(tempDir.resolve("level.dat"), raw.toByteArray());

        String result = LevelDatNameReader.readLevelName(tempDir, "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void missingLevelNameTagReturnsFallback(@TempDir Path tempDir) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(raw); DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("Data");
            // Data compound has some other field but no LevelName.
            out.writeByte(TAG_INT);
            out.writeUTF("SomeOtherField");
            out.writeInt(42);
            out.writeByte(TAG_END); // end Data
            out.writeByte(TAG_END); // end root
        }
        Files.write(tempDir.resolve("level.dat"), raw.toByteArray());

        String result = LevelDatNameReader.readLevelName(tempDir, "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void corruptGzipReturnsFallback(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("level.dat"), new byte[] { 1, 2, 3, 4, 5 });

        String result = LevelDatNameReader.readLevelName(tempDir, "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void truncatedFileReturnsFallback(@TempDir Path tempDir) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(raw); DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("Data");
            out.writeByte(TAG_STRING);
            out.writeUTF("LevelName");
            // Truncated: no string value bytes, no end tags.
        }
        byte[] truncated = raw.toByteArray();
        byte[] halved = new byte[truncated.length / 2];
        System.arraycopy(truncated, 0, halved, 0, halved.length);
        Files.write(tempDir.resolve("level.dat"), halved);

        String result = LevelDatNameReader.readLevelName(tempDir, "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    private static void writeLevelDat(Path folder, String levelName) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(raw); DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("Data");
            out.writeByte(TAG_INT);
            out.writeUTF("SomeIntField");
            out.writeInt(7);
            out.writeByte(TAG_STRING);
            out.writeUTF("LevelName");
            out.writeUTF(levelName);
            out.writeByte(TAG_END); // end Data
            out.writeByte(TAG_END); // end root
        }
        Files.write(folder.resolve("level.dat"), raw.toByteArray());
    }
}
