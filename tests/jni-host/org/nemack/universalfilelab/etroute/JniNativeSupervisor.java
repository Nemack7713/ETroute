package org.nemack.universalfilelab.etroute;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.*;
public class JniNativeSupervisor {
    static { System.load(System.getProperty("etroute.jni.library")); }
    private native long nativeCancellationVersion();
    private native long[] nativeRunCancellable(String exe, String[] argv, String[] env, String cwd, String out, String err, long timeout, long grace, long cpu, long files, long size, long address, AtomicBoolean stop);
    public static void main(String[] args) throws Exception {
        var supervisor = new JniNativeSupervisor();
        if(supervisor.nativeCancellationVersion()!=1) throw new AssertionError();
        var dir=Files.createTempDirectory("etroute-jni-");
        var token=new AtomicBoolean(true);
        long[] early=supervisor.nativeRunCancellable("/bin/sh",new String[]{"/bin/sh","-c","exit 0"},new String[]{},dir.toString(),dir.resolve("out").toString(),dir.resolve("err").toString(),3000,30,0,0,0,0,token);
        if(early.length!=7 || early[0]!=1 || early[6]!=14 || early[3]!=0) throw new AssertionError();
        token.set(false);
        var stopper=new Thread(()->{try {Thread.sleep(100);} catch(InterruptedException e){throw new RuntimeException(e);} token.set(true);});
        stopper.start();
        long[] active=supervisor.nativeRunCancellable("/bin/sh",new String[]{"/bin/sh","-c","while :; do :; done"},new String[]{},dir.toString(),dir.resolve("out").toString(),dir.resolve("err").toString(),3000,30,0,0,0,0,token);
        stopper.join();
        if(active[6]!=14 || active[3]!=0) throw new AssertionError();
        Files.deleteIfExists(dir.resolve("out")); Files.deleteIfExists(dir.resolve("err")); Files.delete(dir);
        System.out.println("JNI token crossing and cancellation: PASS");
    }
}
