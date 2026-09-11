package cn.jia.agent.output.service;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class InspectingInputStream extends FilterInputStream {
    static final class SizeLimitExceededException extends IOException {
        SizeLimitExceededException() { super("Upload size exceeded"); }
    }
    interface LeaseRenewer { void renew(long now) throws IOException; }
    private static final int SAMPLE_LIMIT=2*1024*1024;
    private final MessageDigest digest;
    private final ByteArrayOutputStream sample=new ByteArrayOutputStream();
    private final long limit;
    private final LeaseRenewer renewer;
    private long count; private long nextRenew; private boolean eof;

    InspectingInputStream(InputStream input,long limit,LeaseRenewer renewer){
        super(input);this.limit=limit;this.renewer=renewer;this.nextRenew=System.currentTimeMillis()+30_000L;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
    @Override public int read(byte[] b,int off,int len)throws IOException{int n=super.read(b,off,len);if(n<0){eof=true;return n;}if(n>0){count=Math.addExact(count,n);if(count>limit)throw new SizeLimitExceededException();digest.update(b,off,n);int keep=Math.min(n,SAMPLE_LIMIT-sample.size());if(keep>0)sample.write(b,off,keep);long now=System.currentTimeMillis();if(now>=nextRenew){renewer.renew(now);nextRenew=now+30_000L;}}return n;}
    long count(){return count;} boolean eof(){return eof;} byte[] hash(){return digest.digest();} byte[] sample(){return sample.toByteArray();}
}
