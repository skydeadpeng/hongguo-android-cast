package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

/** Real production connector; fake sockets only, no DNS/socket/network access. */
public final class NetworkGuardAddressTest {
    static int checks,passed,failed;
    interface Checked {void run() throws Exception;}
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static void group(String name,Checked test){try{test.run();passed++;System.out.println("PASS "+name);}catch(Throwable e){failed++;System.out.println("FAIL "+name+": "+e);}finally{Thread.interrupted();}}
    static InetAddress ip(int last)throws Exception{return InetAddress.getByAddress(new byte[]{8,8,4,(byte)last});}
    static class FakeSocket extends Socket {
        final IOException failure;final boolean interrupt;final long delay;boolean closed;InetSocketAddress target;int timeout;
        FakeSocket(IOException failure,boolean interrupt,long delay){this.failure=failure;this.interrupt=interrupt;this.delay=delay;}
        @Override public void connect(SocketAddress target,int timeout)throws IOException {
            this.target=(InetSocketAddress)target;this.timeout=timeout;
            if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("fixture interrupted");}
            if(interrupt)Thread.currentThread().interrupt();
            if(failure!=null)throw failure;
        }
        @Override public void close(){closed=true;}
    }
    static class Sockets implements Supplier<Socket> {
        final List<FakeSocket> sockets=new ArrayList<>();int count;
        Sockets(FakeSocket...rows){sockets.addAll(Arrays.asList(rows));}
        public Socket get(){check(count<sockets.size(),"no unexpected socket allocation");return sockets.get(count++);}
    }
    static FakeSocket fail(IOException e){return new FakeSocket(e,false,0);}
    static Socket connect(InetAddress[] addresses,long deadline,Supplier<Socket>sockets)throws Exception{
        final Method method;
        try{method=NetworkGuard.class.getDeclaredMethod("connectPublic",InetAddress[].class,long.class,Supplier.class);}catch(NoSuchMethodException e){throw new AssertionError("missing checked-address fallback connector");}
        method.setAccessible(true);
        try{return(Socket)method.invoke(null,addresses,deadline,sockets);}catch(InvocationTargetException e){if(e.getCause()instanceof Exception)throw(Exception)e.getCause();throw(Error)e.getCause();}
    }
    static Exception rejected(Checked work)throws Exception{try{work.run();throw new AssertionError("expected rejection");}catch(IOException e){return e;}}
    static long deadline(){return System.nanoTime()+1_000_000_000L;}
    public static void main(String[]args)throws Exception{
        group("failed first CDN address falls back to second checked address",()->{
            FakeSocket a=fail(new ConnectException("fixture first down")),b=fail(null);Sockets f=new Sockets(a,b);
            Socket result=connect(new InetAddress[]{ip(1),ip(2)},deadline(),f);
            check(result==b&&f.count==2,"second address returned");check(a.closed&&!b.closed,"failed socket closed, successful socket owned by caller");
            check(b.target.getAddress().equals(ip(2))&&b.target.getPort()==443,"checked IP and HTTPS port preserved");
            check(a.timeout>0&&a.timeout<=1000&&b.timeout>0&&b.timeout<=1000,"connect timeout follows remaining shared budget");result.close();check(b.closed,"caller closes successful socket");
        });
        group("unreachable IPv6 address permits checked IPv4 fallback",()->{
            byte[] bytes=new byte[16];bytes[0]=0x26;bytes[1]=0x06;bytes[15]=1;
            FakeSocket a=fail(new NoRouteToHostException("fixture v6 unreachable")),b=fail(null);Sockets f=new Sockets(a,b);
            Socket result=connect(new InetAddress[]{InetAddress.getByAddress(bytes),ip(3)},deadline(),f);check(result==b&&a.closed,"v6 failure does not discard valid v4");result.close();
        });
        group("all addresses failing returns final typed cause and closes every socket",()->{
            IOException last=new SocketTimeoutException("fixture final timeout");FakeSocket a=fail(new ConnectException("fixture first")),b=fail(last);Sockets f=new Sockets(a,b);
            Exception e=rejected(()->connect(new InetAddress[]{ip(1),ip(2)},deadline(),f));check(e==last&&f.count==2,"typed final error retained");check(a.closed&&b.closed,"all failed sockets closed");
        });
        group("address attempts capped at eight without extending deadline",()->{
            InetAddress[]addresses=new InetAddress[12];FakeSocket[]sockets=new FakeSocket[8];for(int i=0;i<addresses.length;i++)addresses[i]=ip(i+1);for(int i=0;i<sockets.length;i++)sockets[i]=fail(new ConnectException("fixture down"));Sockets f=new Sockets(sockets);
            rejected(()->connect(addresses,deadline(),f));check(f.count==8,"maximum eight checked-address attempts");for(FakeSocket s:sockets)check(s.closed,"bounded failed socket closed");
        });
        group("expired and elapsed shared deadlines never publish a connection",()->{
            Sockets unused=new Sockets();Exception e=rejected(()->connect(new InetAddress[]{ip(1)},System.nanoTime()-1,unused));check(e instanceof SocketTimeoutException&&unused.count==0,"expired deadline allocates nothing");
            FakeSocket slow=new FakeSocket(null,false,35);Sockets f=new Sockets(slow);e=rejected(()->connect(new InetAddress[]{ip(1)},System.nanoTime()+10_000_000L,f));check(e instanceof SocketTimeoutException&&slow.closed,"late connect closed before HTTP/TLS");check(slow.timeout>0&&slow.timeout<=10,"timeout reduced to remaining milliseconds");
        });
        group("private address anywhere rejects complete DNS set before dialing",()->{
            InetAddress local=InetAddress.getByAddress(new byte[]{127,0,0,1});Sockets f=new Sockets();rejected(()->connect(new InetAddress[]{ip(1),local},deadline(),f));check(f.count==0,"no partial-set bypass");
            rejected(()->connect(new InetAddress[]{ip(1),null},deadline(),f));check(f.count==0,"null DNS answer rejected");
            Exception e=rejected(()->connect(new InetAddress[0],deadline(),f));check(e instanceof UnknownHostException&&f.count==0,"empty answer typed DNS failure");
        });
        group("thread and typed cancellation stop fallback immediately",()->{
            Sockets unused=new Sockets();Thread.currentThread().interrupt();Exception e;
            try{e=rejected(()->connect(new InetAddress[]{ip(1)},deadline(),unused));}finally{Thread.interrupted();}check(e instanceof InterruptedIOException&&unused.count==0,"pre-cancel allocates nothing");
            FakeSocket a=fail(new InterruptedIOException("fixture cancelled"));Sockets f=new Sockets(a);e=rejected(()->connect(new InetAddress[]{ip(1),ip(2)},deadline(),f));check(e instanceof InterruptedIOException&&f.count==1&&a.closed,"typed cancellation closes and does not advance");
            FakeSocket interrupted=new FakeSocket(null,true,0);Sockets g=new Sockets(interrupted);
            try{e=rejected(()->connect(new InetAddress[]{ip(1),ip(2)},deadline(),g));}finally{Thread.interrupted();}check(e instanceof InterruptedIOException&&g.count==1&&interrupted.closed,"cancel during connect cannot publish a socket");
        });
        System.out.println("PUBLIC_SOCKET_"+(failed==0?"OK":"FAILED")+" groupsPassed="+passed+" groupsFailed="+failed+" checks="+checks+" networkRequests=0 actualSocketConnects=0 deviceTests=not_run");
        if(failed>0)throw new AssertionError("public connector regression gate failed");
    }
}
