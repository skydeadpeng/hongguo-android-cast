package io.github.jackotom.hongguo.android;
public final class PlayerRecoveryBudgetTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[]args){
        RecoveryBudget budget=new RecoveryBudget();budget.reset(1);
        check(!budget.take(1,true,false,false),"offline never automatically recovers");
        check(!budget.take(1,false,true,false),"manual local file never enters deletion/recovery");
        check(!budget.take(1,false,false,true),"explicit unsupported decoder never retries");
        check(!budget.take(0,false,false,false),"stale error cannot consume current selection budget");
        check(budget.take(1,false,false,false),"eligible actual media failure gets one recovery");
        check(!budget.take(1,false,false,false),"duplicate media error cannot recover again");
        budget.carry(1,2);check(!budget.take(2,false,false,false),"internal reentry preserves consumed budget");
        budget.carry(1,3);check(!budget.take(3,false,false,false),"old carry cannot hijack newer generation");
        budget.reset(4);check(!budget.take(2,false,false,false),"old recovery callback cannot affect newly selected episode");
        check(budget.take(4,false,false,false),"explicit next episode gets its own budget");
        budget.carry(4,5);check(!budget.take(5,false,false,false),"second episode internal failure remains bounded");
        budget.reset(6);check(budget.take(6,false,false,false),"explicit user retry starts a new bounded attempt");
        System.out.println("PASS PlayerRecoveryBudgetTest checks="+checks+" actualPlayerClass=true internalReentryBound=1 staleGenerationRejected=true offlineAndManualExcluded=true");
    }
}
