package br.com.branlycrm.contact.services;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.contact.domain.Tag;
import br.com.branlycrm.contact.domain.TagName;
import br.com.branlycrm.contact.dto.request.TagRequest;
import br.com.branlycrm.contact.dto.response.TagView;
import br.com.branlycrm.contact.repositories.TagRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Tag catalog of the selected clinic (PAC-06). Whoever writes contacts creates tags; renaming and
 * deleting, which change every contact using the tag, need TAG_MANAGE.
 */
@Service
public class TagService {
  private final TenantAccessService access;
  private final TagRepository tags;

  public TagService(TenantAccessService access, TagRepository tags) {
    this.access = access;
    this.tags = tags;
  }

  public List<TagView> list(UUID tenantId, UUID actor) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_READ);
    return tags.list(clinic).stream().map(TagService::view).toList();
  }

  public TagView create(UUID tenantId, UUID actor, TagRequest request) {
    TagName name = name(request);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_WRITE);
    return view(tags.create(clinic, name));
  }

  public TagView rename(UUID tenantId, UUID actor, UUID tagId, TagRequest request) {
    TagName name = name(request);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.TAG_MANAGE);
    return view(tags.rename(clinic, tagId, name));
  }

  /** Deletes the tag and removes it from every contact. */
  public void delete(UUID tenantId, UUID actor, UUID tagId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.TAG_MANAGE);
    tags.delete(clinic, tagId);
  }

  static TagView view(Tag tag) {
    return new TagView(tag.id(), tag.name().value());
  }

  private static TagName name(TagRequest request) {
    if (request == null) throw new InvalidInputException("body");
    return new TagName(request.name());
  }
}
