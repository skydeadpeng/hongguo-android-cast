package io.github.jackotom.hongguo.android;
/** Baseline compiles production SDK source, then executes its actual choice handler. */
public final class AdaptiveBaselineTest {
 public static void main(String[]args)throws Exception{
  AdaptiveActivityHarness p=new AdaptiveActivityHarness();p.selectEpisode();p.dialog.click(p.current);
  int failures=0;
  if(p.loads!=1){failures++;System.out.println("FAIL BASELINE currentEpisodeConfirm expectedLoad=1 actualLoad="+p.loads);}
  try{Class.forName("io.github.jackotom.hongguo.android.AdaptiveQuality");}catch(ClassNotFoundException absent){failures++;System.out.println("FAIL BASELINE boundedAdaptiveQuality expected=present actual=absent");}
  if(failures>0)throw new AssertionError("original 0.8 behavior failures="+failures);
 }
}
