package io.openfednow.shadowledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Chooses the {@link ReconciliationLock} for the configured database — issue #68.
 *
 * <p>PostgreSQL (every supported deployment) gets the cross-instance advisory
 * lock. Any other database — in practice the H2 database used by the default
 * test profile — gets {@link ReconciliationLock#LOCAL_ONLY} and a startup
 * warning, because it cannot coordinate several instances.
 */
@Configuration
public class ReconciliationLockConfig {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationLockConfig.class);

    @Bean
    public ReconciliationLock reconciliationLock(DataSource dataSource) {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot determine the database for the reconciliation lock", e);
        }
        if ("PostgreSQL".equalsIgnoreCase(product)) {
            log.info("Reconciliation cycles are coordinated across instances by a PostgreSQL advisory lock");
            return new PostgresAdvisoryReconciliationLock(dataSource);
        }
        log.warn("Database {} has no cross-instance advisory lock: reconciliation is guarded within "
                + "this instance only. Run a single instance, or use PostgreSQL.", product);
        return ReconciliationLock.LOCAL_ONLY;
    }
}
