package com.buffalodyl.btcpayservercloverplugin;

public final class TipFlowConfig {
    // Active tip collection strategy for the standalone POS flow.
    // LOCAL_DIALOG is the current default because Clover's native RequestTip flow
    // still crashes on the emulator, even when launched from the POS app.
    private static final TipFlowMode ACTIVE_TIP_FLOW = TipFlowMode.LOCAL_DIALOG;

    private TipFlowConfig() {
    }

    public static TipFlowMode getActiveTipFlow() {
        return ACTIVE_TIP_FLOW;
    }
}
