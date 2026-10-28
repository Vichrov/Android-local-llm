package com.vichrov.localai;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LlmOperationGateTest {
    @Test public void currentOperationIsPrepared() {
        AtomicInteger current=new AtomicInteger(7);
        AtomicBoolean prepared=new AtomicBoolean(false);
        assertTrue(LlmOperationGate.prepare(7,current::get,()->prepared.set(true)));
        assertTrue(prepared.get());
    }

    @Test public void staleOperationNeverClearsCancellation() {
        AtomicInteger current=new AtomicInteger(8);
        AtomicBoolean prepared=new AtomicBoolean(false);
        assertFalse(LlmOperationGate.prepare(7,current::get,()->prepared.set(true)));
        assertFalse(prepared.get());
    }

    @Test public void cancellationDuringPrepareStopsBeforeLoad() {
        AtomicInteger current=new AtomicInteger(7);
        assertFalse(LlmOperationGate.prepare(7,current::get,current::incrementAndGet));
        assertEquals(8,current.get());
    }
}
