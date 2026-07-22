package dev.simulated_team.simulated.compat.naturescompass;

import com.chaosthedude.naturescompass.NaturesCompass;
import dev.simulated_team.simulated.Simulated;

public class NaturesCompassRegistry {
    public static void init() {
        Simulated.getRegistrate().navTarget("natures_compass", NaturesCompassNavigationTarget::new, () -> NaturesCompass.naturesCompass);
    }
}
