package dev.simulated_team.simulated.api;

import net.createmod.catnip.lang.LangBuilder;

public interface CustomStressImpactTooltipProvider {

    /**
     * @return The lang associated with the tooltip. IE 2 x RPM x 'AeroLang.translate("propeller.sails");'
     */
    LangBuilder getCustomImpactLang();

    /**
     * @return How many bar segments there should be in the tooltip
     */
    int getBarLength();

    /**
     * @return How many bar segments should be filled in the tooltip
     */
    int getFilledBarLength();

}
