package io.github.jackotom.hongguo.android;

public final class MobileLayoutBaselineTest {
    public static void main(String[] args){
        MainLayoutHarness main=new MainLayoutHarness();
        main.applyWebInsets(main.webContainer,0,24,0,24,320);
        if(main.webContainer.bottom!=320)throw new AssertionError("baseline MainActivity ignores IME: expected=320 actual="+main.webContainer.bottom);
        throw new AssertionError("baseline is missing production phone reflow and configuration policy");
    }
}
