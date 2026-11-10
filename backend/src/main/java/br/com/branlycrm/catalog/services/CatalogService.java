package br.com.branlycrm.catalog.services;

import br.com.branlycrm.catalog.domain.Procedure;
import br.com.branlycrm.catalog.domain.ProcedureFields;
import br.com.branlycrm.catalog.domain.Professional;
import br.com.branlycrm.catalog.dto.request.ActiveRequest;
import br.com.branlycrm.catalog.dto.request.ProcedureRequest;
import br.com.branlycrm.catalog.dto.request.ProfessionalRequest;
import br.com.branlycrm.catalog.dto.response.ProcedureView;
import br.com.branlycrm.catalog.dto.response.ProfessionalView;
import br.com.branlycrm.catalog.repositories.ProcedureRepository;
import br.com.branlycrm.catalog.repositories.ProfessionalRepository;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Procedures and professionals of the selected clinic (CRM-034). Everyone who sees leads reads the
 * catalog, because recording a sale needs it; only COMMERCIAL_SETTINGS_EDIT (owner and manager)
 * changes it.
 */
@Service
public class CatalogService {
  private final TenantAccessService access;
  private final ProcedureRepository procedures;
  private final ProfessionalRepository professionals;

  public CatalogService(
      TenantAccessService access,
      ProcedureRepository procedures,
      ProfessionalRepository professionals) {
    this.access = access;
    this.procedures = procedures;
    this.professionals = professionals;
  }

  public List<ProcedureView> listProcedures(UUID tenantId, UUID actor, String includeInactive) {
    boolean all = flag(includeInactive);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return procedures.list(clinic, all).stream().map(CatalogService::view).toList();
  }

  public ProcedureView createProcedure(UUID tenantId, UUID actor, ProcedureRequest request) {
    ProcedureFields fields = fields(request);
    return view(procedures.create(edit(tenantId, actor), fields));
  }

  public ProcedureView updateProcedure(
      UUID tenantId, UUID actor, UUID procedureId, ProcedureRequest request) {
    ProcedureFields fields = fields(request);
    long version = version(request.version());
    return view(procedures.update(edit(tenantId, actor), procedureId, version, fields));
  }

  public ProcedureView setProcedureActive(
      UUID tenantId, UUID actor, UUID procedureId, ActiveRequest request) {
    boolean active = active(request);
    return view(procedures.setActive(edit(tenantId, actor), procedureId, active));
  }

  public List<ProfessionalView> listProfessionals(
      UUID tenantId, UUID actor, String includeInactive) {
    boolean all = flag(includeInactive);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return professionals.list(clinic, all).stream().map(CatalogService::view).toList();
  }

  public ProfessionalView createProfessional(
      UUID tenantId, UUID actor, ProfessionalRequest request) {
    String name = name(request);
    return view(professionals.create(edit(tenantId, actor), name));
  }

  public ProfessionalView renameProfessional(
      UUID tenantId, UUID actor, UUID professionalId, ProfessionalRequest request) {
    String name = name(request);
    long version = version(request.version());
    return view(professionals.rename(edit(tenantId, actor), professionalId, version, name));
  }

  public ProfessionalView setProfessionalActive(
      UUID tenantId, UUID actor, UUID professionalId, ActiveRequest request) {
    boolean active = active(request);
    return view(professionals.setActive(edit(tenantId, actor), professionalId, active));
  }

  private ClinicAccess edit(UUID tenantId, UUID actor) {
    return access.require(tenantId, actor, ClinicPermission.COMMERCIAL_SETTINGS_EDIT);
  }

  private static ProcedureView view(Procedure procedure) {
    ProcedureFields fields = procedure.fields();
    return new ProcedureView(
        procedure.id(),
        fields.name(),
        fields.referencePriceCents(),
        fields.category(),
        fields.returnIntervalDays(),
        procedure.active(),
        procedure.version());
  }

  private static ProfessionalView view(Professional professional) {
    return new ProfessionalView(
        professional.id(), professional.name(), professional.active(), professional.version());
  }

  private static ProcedureFields fields(ProcedureRequest request) {
    if (request == null) throw new InvalidInputException("body");
    if (request.referencePriceCents() == null) {
      throw new InvalidInputException("referencePriceCents");
    }
    return new ProcedureFields(
        request.name(),
        request.referencePriceCents(),
        request.category(),
        request.returnIntervalDays());
  }

  private static String name(ProfessionalRequest request) {
    if (request == null) throw new InvalidInputException("body");
    return Professional.name(request.name());
  }

  private static boolean active(ActiveRequest request) {
    if (request == null || request.active() == null) throw new InvalidInputException("active");
    return request.active();
  }

  private static long version(Long version) {
    if (version == null || version < 0) throw new InvalidInputException("version");
    return version;
  }

  private static boolean flag(String value) {
    if (value == null || value.isEmpty() || "false".equals(value)) return false;
    if ("true".equals(value)) return true;
    throw new InvalidInputException("includeInactive");
  }
}
