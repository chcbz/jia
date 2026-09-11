package cn.jia.agent.output.service;

import cn.jia.agent.output.OutputObjectStorage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** S3-compatible adapter using one conditional HTTP PUT; it never invokes multipart APIs. */
public final class S3OutputObjectStorage implements OutputObjectStorage, AutoCloseable {
    private final S3Client client;
    private final String requiredBucket;

    public S3OutputObjectStorage(String endpoint, String accessKey, String secretKey, String bucket) {
        require(endpoint, "storage endpoint"); require(accessKey, "storage access key");
        require(secretKey, "storage secret key"); require(bucket, "storage bucket");
        this.requiredBucket = bucket;
        this.client = S3Client.builder().endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        String status = client.getBucketVersioning(GetBucketVersioningRequest.builder()
                .bucket(bucket).build()).statusAsString();
        if (status != null && !status.isBlank()) {
            throw new IllegalStateException("Output storage bucket must never have enabled versioning");
        }
    }

    @Override public Stored putCreateOnly(String bucket,String key,InputStream input,long length,
                                           String contentType,long deadline) throws IOException {
        requireBucket(bucket); if (length < 0) throw new IOException("Invalid object length");
        try {
            Duration timeout = remaining(deadline);
            PutObjectRequest request = PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType(contentType).ifNoneMatch("*")
                    .overrideConfiguration(c -> c.apiCallTimeout(timeout)).build();
            PutObjectResponse response = client.putObject(request, RequestBody.fromInputStream(input,length));
            return new Stored(response.versionId(), response.eTag());
        } catch (S3Exception e) {
            if (e.statusCode() == 412) throw new IOException("Conditional object key already exists");
            throw new IOException("Object storage PUT failed", e);
        } catch (SdkException e) { throw new IOException("Object storage PUT failed", e); }
    }

    @Override public InputStream open(String bucket,String key,String version) throws IOException {
        requireBucket(bucket);
        try {
            GetObjectRequest.Builder request=GetObjectRequest.builder().bucket(bucket).key(key);
            if(version!=null&&!version.isBlank())request.versionId(version);
            ResponseInputStream<GetObjectResponse> stream=client.getObject(request.build());
            return stream;
        } catch (SdkException e) { throw new IOException("Object storage GET failed",e); }
    }

    @Override public void putTombstone(String bucket,String key,String token,long deadline) throws IOException {
        requireBucket(bucket); require(token,"cleanup token");
        try {
            Duration timeout = remaining(deadline);
            PutObjectRequest request=PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType("application/x-cyf-output-tombstone")
                    .metadata(Map.of("cleanup-token",token))
                    .overrideConfiguration(c->c.apiCallTimeout(timeout)).build();
            client.putObject(request,RequestBody.empty());
        } catch (SdkException e) { throw new IOException("Object storage tombstone PUT failed",e); }
    }

    @Override public Head head(String bucket,String key) throws IOException {
        requireBucket(bucket);
        try {
            HeadObjectResponse response=client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).build());
            return new Head(response.contentLength(),response.versionId(),
                    response.metadata().get("cleanup-token"));
        } catch (SdkException e) { throw new IOException("Object storage HEAD failed",e); }
    }

    @Override public void delete(String bucket,String key,String version) throws IOException {
        requireBucket(bucket);
        try {
            DeleteObjectRequest.Builder request=DeleteObjectRequest.builder().bucket(bucket).key(key);
            if(version!=null&&!version.isBlank())request.versionId(version);
            client.deleteObject(request.build());
        } catch (SdkException e) { throw new IOException("Object storage DELETE failed",e); }
    }

    private void requireBucket(String bucket){if(!Objects.equals(requiredBucket,bucket))throw new IllegalArgumentException("Unexpected output bucket");}
    private static Duration remaining(long deadline)throws IOException{long millis=deadline-System.currentTimeMillis();if(millis<1)throw new IOException("Object I/O deadline expired");return Duration.ofMillis(millis);}
    private static void require(String value,String label){if(value==null||value.isBlank())throw new IllegalArgumentException(label+" is required");}
    @Override public void close(){client.close();}
}
