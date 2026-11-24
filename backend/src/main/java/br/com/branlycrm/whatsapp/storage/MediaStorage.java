package br.com.branlycrm.whatsapp.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Private object storage of WhatsApp media (CRM-084b1, ADR-036, ADR-037): OCI Object Storage in São
 * Paulo through its S3-compatible API (path-style). The access key comes from mounted files, never
 * from environment variables, and never reaches logs. Without configuration the media stay PENDING
 * and links answer 503; the rest of the system works.
 *
 * <p>Keys are built by the caller from database rows only (never from a client).
 */
@Component
public class MediaStorage implements DisposableBean {
  public static final Duration LINK_TTL = Duration.ofMinutes(5);

  private final String bucket;
  private final String endpoint;
  private final String region;
  private final Path credentials;
  private volatile Clients clients;

  public MediaStorage(
      @Value("${app.whatsapp.media.bucket:}") String bucket,
      @Value("${app.whatsapp.media.endpoint:}") String endpoint,
      @Value("${app.whatsapp.media.region:sa-saopaulo-1}") String region,
      @Value("${app.whatsapp.media.credentials-directory:}") String credentials) {
    this.bucket = bucket == null ? "" : bucket.strip();
    this.endpoint = validEndpoint(endpoint);
    this.region = region;
    this.credentials = credentials == null || credentials.isBlank() ? null : Path.of(credentials);
  }

  /** Failure of the storage, with a stable code; never a key, URL or credential. */
  public static final class StorageException extends RuntimeException {
    public StorageException(String code) {
      super(code);
    }
  }

  public boolean configured() {
    return clients().isPresent();
  }

  /** Stores the file under {@code key}, private. */
  public void put(String key, Path file, String contentType, long size) {
    Clients current = clients().orElseThrow(() -> new StorageException("STORAGE_NOT_CONFIGURED"));
    try {
      current
          .s3()
          .putObject(
              PutObjectRequest.builder()
                  .bucket(bucket)
                  .key(key)
                  .contentType(contentType)
                  .contentLength(size)
                  .build(),
              RequestBody.fromFile(file));
    } catch (SdkException failure) {
      throw new StorageException("STORAGE_UNAVAILABLE");
    }
  }

  /**
   * A temporary link ({@link #LINK_TTL}) that downloads the object as an attachment with the given
   * type, so a browser never renders a stored file inline.
   */
  public String link(String key, String contentType, String fileName) {
    Clients current = clients().orElseThrow(() -> new StorageException("STORAGE_NOT_CONFIGURED"));
    try {
      return current
          .presigner()
          .presignGetObject(
              presign ->
                  presign
                      .signatureDuration(LINK_TTL)
                      .getObjectRequest(
                          GetObjectRequest.builder()
                              .bucket(bucket)
                              .key(key)
                              .responseContentType(contentType)
                              .responseContentDisposition(
                                  "attachment; filename=\"" + fileName + "\"")
                              .build()))
          .url()
          .toString();
    } catch (SdkException failure) {
      throw new StorageException("STORAGE_UNAVAILABLE");
    }
  }

  /** An http(s) URL with a host; anything else leaves the storage unconfigured. */
  private static String validEndpoint(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) return "";
    try {
      URI uri = URI.create(endpoint.strip());
      boolean web = "https".equals(uri.getScheme()) || "http".equals(uri.getScheme());
      return web && uri.getHost() != null ? endpoint.strip() : "";
    } catch (IllegalArgumentException invalid) {
      return "";
    }
  }

  /** Until the key files appear, they are looked for again at most once a minute. */
  private volatile long missingUntil;

  private Optional<Clients> clients() {
    Clients current = clients;
    if (current != null) return Optional.of(current);
    if (bucket.isEmpty() || endpoint.isEmpty() || credentials == null) return Optional.empty();
    if (System.nanoTime() < missingUntil) return Optional.empty();
    synchronized (this) {
      if (clients != null) return Optional.of(clients);
      Optional<AwsBasicCredentials> keys = keys();
      if (keys.isEmpty()) {
        missingUntil = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        return Optional.empty();
      }
      URI uri = URI.create(endpoint);
      StaticCredentialsProvider provider = StaticCredentialsProvider.create(keys.get());
      S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
      S3Client s3 =
          S3Client.builder()
              .httpClient(UrlConnectionHttpClient.create())
              .endpointOverride(uri)
              .region(Region.of(region))
              .serviceConfiguration(pathStyle)
              .credentialsProvider(provider)
              // OCI and MinIO do not accept the newer default checksum headers on every call.
              .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
              .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
              .build();
      S3Presigner presigner =
          S3Presigner.builder()
              .endpointOverride(uri)
              .region(Region.of(region))
              .serviceConfiguration(pathStyle)
              .credentialsProvider(provider)
              .build();
      clients = new Clients(s3, presigner);
      return Optional.of(clients);
    }
  }

  /** {@code access-key-id} and {@code secret-access-key} files (the OCI Customer Secret Key). */
  private Optional<AwsBasicCredentials> keys() {
    try {
      Path id = credentials.resolve("access-key-id");
      Path secret = credentials.resolve("secret-access-key");
      if (!Files.isRegularFile(id) || !Files.isRegularFile(secret)) return Optional.empty();
      if (Files.size(id) > 256 || Files.size(secret) > 256) return Optional.empty();
      String idValue = Files.readString(id, StandardCharsets.UTF_8).strip();
      String secretValue = Files.readString(secret, StandardCharsets.UTF_8).strip();
      if (idValue.isEmpty() || secretValue.isEmpty()) return Optional.empty();
      return Optional.of(AwsBasicCredentials.create(idValue, secretValue));
    } catch (IOException | RuntimeException unreadable) {
      return Optional.empty();
    }
  }

  @Override
  public void destroy() {
    Clients current = clients;
    if (current != null) {
      current.s3().close();
      current.presigner().close();
    }
  }

  private record Clients(S3Client s3, S3Presigner presigner) {
    @Override
    public String toString() {
      return "Clients[redacted]";
    }
  }

  @Override
  public String toString() {
    return "MediaStorage[redacted]";
  }
}
