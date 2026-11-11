package io.github.jackotom.hongguo.android;

public final class UpdateOperationGateTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError(); }
    private static void rejected(Throwing action) throws Exception { checks++; try { action.run(); throw new AssertionError(); } catch (java.io.IOException expected) { } }
    interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        UpdateOperationGate gate = new UpdateOperationGate();
        Object owner = new Object(), other = new Object();
        UpdateOperationGate.Lease a = gate.operation(), b = gate.operation();
        rejected(() -> gate.install(owner)); a.close(); a.close(); rejected(() -> gate.install(owner)); b.close();
        gate.install(owner); gate.release(other); rejected(gate::operation); rejected(() -> gate.install(owner)); gate.release(owner);gate.release(owner);
        try(UpdateOperationGate.Lease c=gate.operation()) {check(c!=null);rejected(() -> gate.install(owner));}
        gate.install(owner);gate.release(owner);check(true);
        System.out.println("UPDATE_OPERATION_GATE_OK checks="+checks);
    }
}
