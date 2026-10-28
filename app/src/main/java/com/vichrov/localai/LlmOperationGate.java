package com.vichrov.localai;

import java.util.function.IntSupplier;

/** Keeps NativeLlm cancellation sticky for one serial load -> generate operation. */
final class LlmOperationGate {
    private LlmOperationGate() {}

    static boolean prepare(int ticket, IntSupplier currentTicket, Runnable nativePrepare) {
        if(ticket != currentTicket.getAsInt()) return false;
        nativePrepare.run();
        return ticket == currentTicket.getAsInt();
    }
}
