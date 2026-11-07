package br.com.branlycrm.identity.mail;

import java.net.URI;

/**
 * Origin of the links in e-mails (APP_PUBLIC_ORIGIN). Never derived from the request Host header,
 * which a client controls.
 */
public record PublicOrigin(URI uri) {
  public PublicOrigin {
    boolean local = "http".equals(uri.getScheme()) && "localhost".equals(uri.getHost());
    if (!local && !"https".equals(uri.getScheme())) {
      throw new IllegalStateException("APP_PUBLIC_ORIGIN must be HTTPS or http://localhost");
    }
    String path = uri.getRawPath();
    if ((path != null && !path.isEmpty()) || uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalStateException("APP_PUBLIC_ORIGIN must be an origin, without path");
    }
  }

  public static PublicOrigin of(String value) {
    return new PublicOrigin(URI.create(value));
  }

  public boolean secure() {
    return "https".equals(uri.getScheme());
  }

  /** The token goes in the fragment, which browsers never send to any server. */
  public String invitationLink(String token) {
    return uri + "/convite#token=" + token;
  }
}
