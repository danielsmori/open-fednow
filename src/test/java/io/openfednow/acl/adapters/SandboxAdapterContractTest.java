package io.openfednow.acl.adapters;

import io.openfednow.acl.core.CoreBankingAdapter;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;

/**
 * Contract tests for {@link SandboxAdapter}.
 *
 * <p>Proves that SandboxAdapter satisfies every requirement in
 * {@link CoreBankingAdapterContractTest}. The adapter is constructed directly
 * (no Spring context) using the package-private test constructor.
 */
class SandboxAdapterContractTest extends CoreBankingAdapterContractTest {

    private SandboxAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SandboxAdapter(true, new BigDecimal("50000.00"), 0L);
    }

    @Override
    protected CoreBankingAdapter adapter() {
        return adapter;
    }
}
