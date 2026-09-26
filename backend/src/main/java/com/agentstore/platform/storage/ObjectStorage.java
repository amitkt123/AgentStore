package com.agentstore.platform.storage;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * S3-compatible object storage (SeaweedFS in dev and M1; spec C5). Pre-signed URLs let the CLI and the runner move
 * bytes without holding credentials. Any S3-compatible store works through config alone.
 */
@Component
public class ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public ObjectStorage(@Value("${agentstore.storage.endpoint}") String endpoint,
                         @Value("${agentstore.storage.region:us-east-1}") String region,
                         @Value("${agentstore.storage.access-key}") String accessKey,
                         @Value("${agentstore.storage.secret-key}") String secretKey,
                         @Value("${agentstore.storage.bucket}") String bucket) {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        URI uri = URI.create(endpoint);
        this.s3 = S3Client.builder()
                .endpointOverride(uri)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                // Non-AWS stores don't all accept the SDK's default CRC32 trailers.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(uri)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        this.bucket = bucket;
    }

    public void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            create();
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw new StorageException("Could not check bucket " + bucket, e);
            }
            create();
        } catch (SdkException e) {
            throw new StorageException("Could not check bucket " + bucket, e);
        }
    }

    private void create() {
        try {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (SdkException e) {
            throw new StorageException("Could not create bucket " + bucket, e);
        }
    }

    public String presignPut(String key, Duration ttl) {
        return presigner.presignPutObject(r -> r.signatureDuration(ttl)
                .putObjectRequest(p -> p.bucket(bucket).key(key))).url().toString();
    }

    public String presignGet(String key, Duration ttl) {
        return presigner.presignGetObject(r -> r.signatureDuration(ttl)
                .getObjectRequest(g -> g.bucket(bucket).key(key))).url().toString();
    }

    public Optional<Long> size(String key) {
        try {
            return Optional.of(s3.headObject(h -> h.bucket(bucket).key(key)).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new StorageException("Could not stat " + key, e);
        } catch (SdkException e) {
            throw new StorageException("Could not stat " + key, e);
        }
    }

    public InputStream open(String key) {
        try {
            return s3.getObject(g -> g.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new StorageException("Could not read " + key, e);
        }
    }

    public void put(String key, byte[] bytes) {
        try {
            s3.putObject(p -> p.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw new StorageException("Could not write " + key, e);
        }
    }

    public void deleteQuietly(String key) {
        try {
            s3.deleteObject(d -> d.bucket(bucket).key(key));
        } catch (SdkException ignored) {
            // an orphaned object costs a few bytes; never fail the caller over it
        }
    }

    @PreDestroy
    void close() {
        presigner.close();
        s3.close();
    }
}
