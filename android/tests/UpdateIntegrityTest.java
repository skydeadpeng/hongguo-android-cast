package io.github.jackotom.hongguo.android;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class UpdateIntegrityTest {
 static int checks;
 static void yes(boolean value){checks++;if(!value)throw new AssertionError("check"+checks);}
 interface Try {void run()throws Exception;}
 static void bad(Try action,String message)throws Exception{checks++;try{action.run();throw new AssertionError("accepted "+checks);}catch(IOException e){if(!e.getMessage().contains(message))throw e;}}
 public static void main(String[] a)throws Exception{
  byte[] data=new byte[100_001];new Random(1).nextBytes(data);ByteArrayOutputStream output=new ByteArrayOutputStream();final long[] progress={0};
  yes(UpdateIntegrity.copy(new ByteArrayInputStream(data),output,data.length,()->{},n->progress[0]=n)==data.length);yes(Arrays.equals(data,output.toByteArray()));yes(progress[0]==data.length);
  bad(()->UpdateIntegrity.copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),data.length+1,()->{},n->{}),"完整");
  bad(()->UpdateIntegrity.copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),data.length-1,()->{},n->{}),"声明");
  bad(()->UpdateIntegrity.copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),0,()->{},n->{}),"无效");
  bad(()->UpdateIntegrity.copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),UpdatePolicy.MAX_APK_BYTES+1,()->{},n->{}),"无效");
  ByteArrayOutputStream cancelled=new ByteArrayOutputStream();final int[] guard={0};
  bad(()->UpdateIntegrity.copy(new ByteArrayInputStream(data),cancelled,data.length,()->{if(++guard[0]>=3)throw new InterruptedIOException("cancelled");},n->{}),"cancelled");yes(cancelled.size()==32768);
  InputStream broken=new InputStream(){public int read()throws IOException{throw new IOException("disconnect");}};
  bad(()->UpdateIntegrity.copy(broken,new ByteArrayOutputStream(),100,()->{},n->{}),"disconnect");
  OutputStream full=new OutputStream(){public void write(int n)throws IOException{throw new IOException("disk full");}};
  bad(()->UpdateIntegrity.copy(new ByteArrayInputStream(data),full,data.length,()->{},n->{}),"disk full");
  Path tmp=Files.createTempFile("hongguo-update-integrity", ".apk");
  try{Files.write(tmp,"abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));yes(UpdateIntegrity.hash(tmp.toFile(),()->{}).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
   Files.write(tmp,new byte[0]);yes(UpdateIntegrity.hash(tmp.toFile(),()->{}).equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
   bad(()->UpdateIntegrity.hash(tmp.toFile(),()->{throw new InterruptedIOException("cancelled");}),"cancelled");
  }finally{Files.deleteIfExists(tmp);}
  System.out.println("UpdateIntegrity: "+checks+" checks passed");
 }
}
