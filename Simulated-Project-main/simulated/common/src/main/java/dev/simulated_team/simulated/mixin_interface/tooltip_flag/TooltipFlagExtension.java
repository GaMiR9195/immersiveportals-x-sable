package dev.simulated_team.simulated.mixin_interface.tooltip_flag;

public interface TooltipFlagExtension {
    /**
     * @return True if this tooltip is being generated for creative menu search
     */
    boolean simulated$getCreativeSearch();
    void simulated$setCreativeSearch(boolean value);
}
