package io.openfednow.shadowledger;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test helper: a mocked {@link ShadowLedger} whose versioned
 * {@link ShadowLedger#snapshotBalance} follows whatever the test stubs for
 * {@link ShadowLedger#getAvailableBalance} (zero when unstubbed), so
 * reconciliation tests written against plain balances keep working.
 */
final class LedgerMocks {

    private LedgerMocks() { }

    static ShadowLedger mockLedger() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.snapshotBalance(any())).thenAnswer(inv -> {
            BigDecimal balance = ledger.getAvailableBalance(inv.getArgument(0));
            return new ShadowLedger.BalanceSnapshot(balance == null ? BigDecimal.ZERO : balance, 0L);
        });
        return ledger;
    }
}
