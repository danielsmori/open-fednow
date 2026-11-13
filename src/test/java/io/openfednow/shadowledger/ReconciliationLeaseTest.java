package io.openfednow.shadowledger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link ReconciliationLease} — issue #68, PR 3.
 *
 * <p>Real H2 with the production migrations, two lease objects sharing one
 * database to play the two instances. The invariants: one holder at a time,
 * re-entrant for its own holder, reclaimable only after expiry, and a renewal
 * after a takeover must fail (that is what makes a lost lease abort a cycle
 * instead of running concurrently).
 */
class ReconciliationLeaseTest {

    private static JdbcTemplate jdbc;

    private ReconciliationLease podA;
    private ReconciliationLease podB;

    @BeforeAll
    static void runMigrations() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:reconciliation_lease_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void freshLease() {
        jdbc.update("UPDATE reconciliation_lease SET holder = NULL, leased_until = NULL WHERE id = 1");
        podA = new ReconciliationLease(jdbc, 900, "pod-a");
        podB = new ReconciliationLease(jdbc, 900, "pod-b");
    }

    @Test
    void freeLeaseIsAcquired() {
        assertThat(podA.tryAcquire()).isTrue();
        assertThat(holder()).isEqualTo("pod-a");
    }

    @Test
    void heldLeaseIsNotAcquiredByAnotherInstance() {
        assertThat(podA.tryAcquire()).isTrue();
        assertThat(podB.tryAcquire()).isFalse();
        assertThat(holder()).isEqualTo("pod-a");
    }

    @Test
    void holdingInstanceReacquiresItsOwnLease() {
        assertThat(podA.tryAcquire()).isTrue();
        assertThat(podA.tryAcquire()).isTrue();
    }

    @Test
    void expiredLeaseIsReclaimable() {
        assertThat(podA.tryAcquire()).isTrue();
        expireNow();

        assertThat(podB.tryAcquire()).isTrue();
        assertThat(holder()).isEqualTo("pod-b");
    }

    @Test
    void renewalExtendsAHeldLease() {
        assertThat(podA.tryAcquire()).isTrue();
        expireNow(); // crashed-pod scenario: time passed, but nobody took it

        assertThat(podA.renew()).isTrue();
        assertThat(podB.tryAcquire()).isFalse();
    }

    @Test
    void renewalFailsAfterATakeover() {
        assertThat(podA.tryAcquire()).isTrue();
        expireNow();
        assertThat(podB.tryAcquire()).isTrue();

        assertThat(podA.renew()).isFalse();
        assertThat(holder()).isEqualTo("pod-b");
    }

    @Test
    void releaseFreesTheLeaseForOthers() {
        assertThat(podA.tryAcquire()).isTrue();
        podA.release();

        assertThat(holder()).isNull();
        assertThat(podB.tryAcquire()).isTrue();
    }

    @Test
    void releaseByANonHolderChangesNothing() {
        assertThat(podA.tryAcquire()).isTrue();
        podB.release();

        assertThat(holder()).isEqualTo("pod-a");
    }

    @Test
    void missingLeaseRowIsRecreatedAndAcquired() {
        // e.g. an operator truncated the table; without self-healing this
        // would report "another instance is reconciling" forever
        jdbc.update("DELETE FROM reconciliation_lease");

        assertThat(podA.tryAcquire()).isTrue();
        assertThat(holder()).isEqualTo("pod-a");
        assertThat(podB.tryAcquire()).isFalse();
    }

    @Test
    void nonPositiveLeaseSecondsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReconciliationLease(jdbc, 0, "pod-x"))
                .withMessageContaining("lease-seconds");
    }

    private String holder() {
        return jdbc.queryForObject(
                "SELECT holder FROM reconciliation_lease WHERE id = 1", String.class);
    }

    /** Backdates the lease expiry so the lease is reclaimable right now. */
    private void expireNow() {
        jdbc.update("UPDATE reconciliation_lease SET leased_until = ? WHERE id = 1",
                Timestamp.from(Instant.now().minusSeconds(5)));
    }
}
