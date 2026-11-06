package br.com.branlycrm.identity.security;

import br.com.branlycrm.identity.exceptions.ClinicNotSelectedException;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;

/**
 * Reads the clinic chosen with {@code PUT /v1/auth/clinic/{id}}. {@link SessionValidationFilter}
 * has already revalidated the membership on this request; services authorize again.
 */
public final class SelectedClinic {
  private SelectedClinic() {}

  public static UUID require(HttpSession session) {
    if (session != null
        && session.getAttribute(SessionValidationFilter.SELECTED_TENANT) instanceof UUID tenantId) {
      return tenantId;
    }
    throw new ClinicNotSelectedException();
  }
}
