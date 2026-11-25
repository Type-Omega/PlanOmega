package com.typeomega.planomega.integration.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;

/**
 * EMI entrypoint for Plan Omega. Enables hovered-stack and hovered-recipe lookup via {@link EmiLookupHelper}.
 */
@EmiEntrypoint
public final class EmiPlanOmegaPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        // Recipe/item lookup from EMI overlays uses EmiApi directly in EmiLookupHelper.
    }
}
