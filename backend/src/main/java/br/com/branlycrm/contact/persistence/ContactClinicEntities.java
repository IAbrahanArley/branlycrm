package br.com.branlycrm.contact.persistence;

import br.com.branlycrm.tenant.persistence.ClinicEntityRegistration;
import java.util.List;
import org.springframework.stereotype.Component;

/** Clinic entities owned by the contact module (ADR-030). */
@Component
class ContactClinicEntities implements ClinicEntityRegistration {
  @Override
  public List<Class<?>> entities() {
    return List.of(
        ContactEntity.class, TagEntity.class, ContactTagEntity.class, ContactConsentEntity.class);
  }
}
