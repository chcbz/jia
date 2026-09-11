package cn.jia.agent.output.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named="OD02_S3_ENDPOINT",matches=".+")
class S3OutputObjectStorageMinioTest {
    private S3Client admin; private String bucket; private S3OutputObjectStorage first; private S3OutputObjectStorage second;

    @BeforeEach void setup(){
        String endpoint=env("OD02_S3_ENDPOINT"),access=env("OD02_S3_ACCESS_KEY"),secret=env("OD02_S3_SECRET_KEY");
        bucket="od02-java-fence-"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        admin=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        first=new S3OutputObjectStorage(endpoint,access,secret,bucket);second=new S3OutputObjectStorage(endpoint,access,secret,bucket);
    }
    @AfterEach void cleanup(){
        if(admin!=null&&bucket!=null){try{admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key("staging/race").build());}catch(Exception ignored){}try{admin.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());}catch(Exception ignored){}}
        if(first!=null)first.close();if(second!=null)second.close();if(admin!=null)admin.close();
    }

    @Test void inFlightCreateOnlyPutCannotRecreateObjectAfterAcknowledgedTombstone()throws Exception{
        byte[] body=new byte[1024*1024];GateInputStream slow=new GateInputStream(body,64*1024);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<?> old=pool.submit(()->{try{first.putCreateOnly(bucket,"staging/race",slow,body.length,"application/octet-stream",System.currentTimeMillis()+30_000);}catch(IOException e){throw new RuntimeException(e);}});
        assertTrue(slow.firstChunkRead.await(10,TimeUnit.SECONDS));
        Future<?> marker=pool.submit(()->{try{second.putTombstone(bucket,"staging/race","cleanup-token",System.currentTimeMillis()+30_000);}catch(IOException failure){throw new RuntimeException(failure);}});
        Thread.sleep(300);
        assertFalse(marker.isDone(),"MinIO must serialize the tombstone behind a partially received same-key PUT");
        slow.release.countDown();old.get(20,TimeUnit.SECONDS);marker.get(20,TimeUnit.SECONDS);
        var head=first.head(bucket,"staging/race");assertEquals(0,head.size());assertEquals("cleanup-token",head.cleanupToken());
        assertThrows(IOException.class,()->first.putCreateOnly(bucket,"staging/race",new ByteArrayInputStream(new byte[]{1}),1,"application/octet-stream",System.currentTimeMillis()+10_000));
        second.putTombstone(bucket,"staging/race","cleanup-token",System.currentTimeMillis()+10_000);
        head=first.head(bucket,"staging/race");assertEquals(0,head.size());assertEquals("cleanup-token",head.cleanupToken());
        pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));
    }

    private static String env(String name){String v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" missing");return v;}
    private static final class GateInputStream extends InputStream{
        private final byte[] bytes;private final int gateAt;private int at;private boolean gated;
        final CountDownLatch firstChunkRead=new CountDownLatch(1),release=new CountDownLatch(1);
        GateInputStream(byte[] bytes,int gateAt){this.bytes=bytes;this.gateAt=gateAt;}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(at>=bytes.length)return -1;if(!gated&&at>=gateAt){gated=true;firstChunkRead.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IOException("gate timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}int n=Math.min(len,Math.min(8192,bytes.length-at));System.arraycopy(bytes,at,b,off,n);at+=n;return n;}
        @Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
    }
}
