package io.openfednow.acl.adapters.fiserv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Opt-in command line read of Fiserv's published Premier Trial DDA fixture. */
public final class PremierTrialProbe {
    private PremierTrialProbe() {}

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !"--read-sample-dda".equals(args[0]))
                throw new IllegalArgumentException("Expected --read-sample-dda");
            Path path = Path.of(System.getProperty("fiserv.trial.config",
                    Path.of(System.getProperty("user.home"), ".config/openfednow/fiserv-trial.env").toString()));
            Map<String, String> values = readConfig(path);
            PremierTrialAdapter adapter = new PremierTrialAdapter(new PremierTrialAdapter.TrialConfig(
                    values.get("FISERV_TRIAL_BASE_URL"), values.get("FISERV_TRIAL_TOKEN_URL"),
                    values.get("FISERV_TRIAL_API_KEY"), values.get("FISERV_TRIAL_API_SECRET"),
                    values.get("FISERV_TRIAL_ORG_ID")));
            if (!"DDA".equals(values.get("FISERV_TRIAL_ACCOUNT_TYPE")))
                throw new IllegalArgumentException("Published DDA fixture is required");
            PremierTrialAdapter.AccountObservation observation = adapter.inspectDda(
                    values.get("FISERV_TRIAL_ACCOUNT_ID"));
            System.out.printf("premier_java_read=pass account_id_matches=%s balance_entries=%d available_funds=unverified%n",
                    observation.accountIdMatched(), observation.balanceEntryCount());
        } catch (Exception error) {
            // Do not include exception messages: network libraries can include request details.
            System.err.println("premier_java_read=not_verified reason=" + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static Map<String, String> readConfig(Path path) throws java.io.IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int split = line.indexOf('=');
            if (split <= 0) throw new IllegalArgumentException("Malformed Trial configuration");
            String key = line.substring(0, split).trim();
            if (values.putIfAbsent(key, line.substring(split + 1).trim()) != null)
                throw new IllegalArgumentException("Duplicate Trial configuration field");
        }
        return values;
    }
}
