package br.com.branlycrm.inbox.domain;

import java.io.IOException;
import java.io.InputStream;

/**
 * A file received by the API to send (CRM-084b2), without HTTP types: the service may read it more
 * than once.
 *
 * @param fileName the name the client gave, only kept (sanitized) for documents
 * @param size the size the request declared; the bytes read are counted again
 */
public record MediaUpload(String fileName, long size, Opener opener) {
  /** Opens the content from the start. */
  @FunctionalInterface
  public interface Opener {
    InputStream open() throws IOException;
  }

  @Override
  public String toString() {
    return "MediaUpload[size=" + size + "]";
  }
}
