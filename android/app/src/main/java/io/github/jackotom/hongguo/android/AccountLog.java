package io.github.jackotom.hongguo.android;

/** Account-chain diagnostics behind an injectable sink: offline harnesses compile this class without the Android SDK. */
final class AccountLog {
    interface Sink { void log(String level, String tag, String message); }
    private static final String TAG="HongguoAccount";
    private static volatile Sink sink;
    private AccountLog(){}
    static void setSink(Sink value){sink=value;}
    static void warn(String message){log("W",message);}
    static void error(String message){log("E",message);}
    /** The sink is optional by design: a broken logger must never break a login attempt. */
    private static void log(String level,String message){Sink current=sink;if(current==null)return;try{current.log(level,TAG,message==null?"":message);}catch(RuntimeException ignored){}}
}
