package io.openfednow.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Rotatable source of the admin username and password.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Static</b> — values come from {@code application.yml} /
 *       {@code openfednow.admin.username} / {@code openfednow.admin.password}
 *       (or their {@code ADMIN_USERNAME} / {@code ADMIN_PASSWORD} env-var
 *       equivalents). This is the sandbox/dev default; changing the password
 *       requires a restart.</li>
 *   <li><b>File-backed</b> — activated when
 *       {@code openfednow.admin.credential-file} is set. On every call the
 *       file is read, and its two lines are treated as the username and
 *       password. The file's modification time is cached so an unchanged file
 *       serves from memory rather than re-reading on every login. This is
 *       the K8s-native rotation pattern: a mounted Secret updates in place,
 *       and the next admin login picks up the new value without a pod
 *       restart or {@code SIGHUP}.</li>
 * </ul>
 *
 * <p>The class is deliberately not annotated as a Spring bean — it is
 * constructed in {@link SecurityConfig} so that the same instance can back
 * the {@code UserDetailsService} lookup path.
 */
public class AdminCredentialSource {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialSource.class);

    private final String staticUsername;
    private final String staticPassword;
    private final Path credentialFile;

    private volatile long cachedMtime = -1L;
    private volatile Snapshot cachedSnapshot;

    public AdminCredentialSource(String staticUsername, String staticPassword, Path credentialFile) {
        this.staticUsername = Objects.requireNonNull(staticUsername, "staticUsername");
        this.staticPassword = Objects.requireNonNull(staticPassword, "staticPassword");
        this.credentialFile = credentialFile;
    }

    /**
     * Returns the current admin credentials. Reads the file on every call
     * only if its modification time has advanced since the last read; that
     * amortises the cost across the hot login path.
     */
    public Snapshot current() {
        if (credentialFile == null) {
            return new Snapshot(staticUsername, staticPassword);
        }
        try {
            long mtime = Files.getLastModifiedTime(credentialFile).toMillis();
            Snapshot cached = cachedSnapshot;
            if (cached != null && mtime == cachedMtime) {
                return cached;
            }
            Snapshot fresh = readFile();
            this.cachedSnapshot = fresh;
            this.cachedMtime = mtime;
            log.info("Admin credential file reloaded (mtime {})", mtime);
            return fresh;
        } catch (IOException e) {
            // Fall back to the static values rather than 500-ing every login
            // attempt. Operators still see the failure via the WARN log line;
            // a permanently-broken file becomes visible without a mass outage.
            log.warn("Failed to read admin credential file at {} — falling back to static config",
                    credentialFile, e);
            return new Snapshot(staticUsername, staticPassword);
        }
    }

    private Snapshot readFile() throws IOException {
        var lines = Files.readAllLines(credentialFile);
        String username = lines.isEmpty() ? staticUsername : lines.get(0).trim();
        String password = lines.size() < 2 ? staticPassword : lines.get(1).trim();
        if (username.isBlank() || password.isBlank()) {
            throw new IOException(
                    "Admin credential file " + credentialFile
                            + " must contain a non-blank username on line 1 and password on line 2");
        }
        return new Snapshot(username, password);
    }

    /** Immutable snapshot returned by {@link #current()}. */
    public record Snapshot(String username, String password) {}
}
