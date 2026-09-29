package io.openfednow.acl.adapters;

import io.openfednow.acl.adapters.fis.FisHttpClient;
import io.openfednow.acl.adapters.fiserv.FiservHttpClient;
import io.openfednow.acl.adapters.jackhenry.JackHenrySoapClient;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreReliabilityCapabilities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** One capability gate applied to all three vendor-shaped adapter classes. */
class VendorReliabilityCapabilityContractTest {
    @Test
    void noVendorShapedFixtureClaimsEnforceableOutboundReservation() {
        List<CoreBankingAdapter> adapters = List.of(
                new FisAdapter(mock(FisHttpClient.class)),
                new FiservAdapter(mock(FiservHttpClient.class)),
                new JackHenryAdapter(mock(JackHenrySoapClient.class)));
        for (CoreBankingAdapter adapter : adapters) {
            CoreReliabilityCapabilities profile = adapter.reliabilityCapabilities();
            assertThat(profile.version()).as(adapter.getVendorName()).isEqualTo("1.0");
            assertThat(profile.balanceRead()).isEqualTo(CoreReliabilityCapabilities.Evidence.LOCAL_FIXTURE);
            assertThat(profile.allChannelReservation()).isEqualTo(CoreReliabilityCapabilities.Evidence.UNSUPPORTED);
            assertThat(profile.reservationLookupAndExpiry()).isEqualTo(CoreReliabilityCapabilities.Evidence.UNSUPPORTED);
            assertThat(profile.lostAcknowledgmentLookup()).isEqualTo(CoreReliabilityCapabilities.Evidence.UNSUPPORTED);
            assertThat(profile.otherChannelCoordination()).isEqualTo(CoreReliabilityCapabilities.Evidence.UNSUPPORTED);
            assertThat(profile.permitsLiveOutboundReservation()).isFalse();
        }
    }
}
