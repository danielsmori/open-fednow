package io.openfednow.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCredentialSourceTest {

    // ── Static (no file) mode ────────────────────────────────────────────────

    @Test
    void currentReturnsStaticSnapshotWhenNoFileConfigured() {
        AdminCredentialSource source = new AdminCredentialSource("admin", "s3cret", null);

        AdminCredentialSource.Snapshot snapshot = source.current();

        assertThat(snapshot.username()).isEqualTo("admin");
        assertThat(snapshot.password()).isEqualTo("s3cret");
    }

    // ── File-backed mode ─────────────────────────────────────────────────────

    @Test
    void currentReadsUsernameAndPasswordFromFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("admin.txt");
        Files.writeString(file, "opsuser\nrotate-me-1\n");

        AdminCredentialSource source = new AdminCredentialSource("static-user", "static-pw", file);

        AdminCredentialSource.Snapshot snapshot = source.current();

        assertThat(snapshot.username()).isEqualTo("opsuser");
        assertThat(snapshot.password()).isEqualTo("rotate-me-1");
    }

    @Test
    void currentReflectsFileMutationsWithoutRebuild(@TempDir Path tmp) throws IOException, InterruptedException {
        // Simulates a K8s Secret rollover: the same mount path stays,
        // but the file's contents (and mtime) change.
        Path file = tmp.resolve("admin.txt");
        Files.writeString(file, "opsuser\noriginal-pw\n");

        AdminCredentialSource source = new AdminCredentialSource("static-user", "static-pw", file);
        assertThat(source.current().password()).isEqualTo("original-pw");

        // Wait long enough for the mtime to advance (filesystem millisecond granularity)
        Thread.sleep(20);
        Files.writeString(file, "opsuser\nrotated-pw\n");

        assertThat(source.current().password()).isEqualTo("rotated-pw");
    }

    @Test
    void currentCachesSnapshotBetweenReadsWhenMtimeUnchanged(@TempDir Path tmp) throws IOException {
        // Read pressure — a rapid burst of logins must NOT hammer the FS on
        // every call. If the mtime hasn't advanced, we return the cached
        // snapshot object identity to demonstrate the cache is hot.
        Path file = tmp.resolve("admin.txt");
        Files.writeString(file, "opsuser\npw\n");

        AdminCredentialSource source = new AdminCredentialSource("static-user", "static-pw", file);
        AdminCredentialSource.Snapshot first = source.current();
        AdminCredentialSource.Snapshot second = source.current();

        assertThat(second).isSameAs(first);
    }

    @Test
    void missingFileFallsBackToStaticValues(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.txt");

        AdminCredentialSource source = new AdminCredentialSource("static-user", "static-pw", missing);

        AdminCredentialSource.Snapshot snapshot = source.current();
        // Ops still get an authenticated path via the static config — a missing
        // file must NOT lock everyone out.
        assertThat(snapshot.username()).isEqualTo("static-user");
        assertThat(snapshot.password()).isEqualTo("static-pw");
    }

    @Test
    void blankLinesFallBackToStaticValues(@TempDir Path tmp) throws IOException {
        // A half-populated file (empty password) shouldn't authenticate anyone
        // with empty credentials.
        Path file = tmp.resolve("admin.txt");
        Files.writeString(file, "opsuser\n\n");

        AdminCredentialSource source = new AdminCredentialSource("static-user", "static-pw", file);

        AdminCredentialSource.Snapshot snapshot = source.current();
        assertThat(snapshot.username()).isEqualTo("static-user");
        assertThat(snapshot.password()).isEqualTo("static-pw");
    }
}
