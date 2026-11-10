package br.com.branlycrm.pipeline.services;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.pipeline.domain.PaymentMethod;
import br.com.branlycrm.pipeline.domain.Sale;
import br.com.branlycrm.pipeline.domain.SaleEvent;
import br.com.branlycrm.pipeline.domain.SaleFields;
import br.com.branlycrm.pipeline.domain.SaleSearch;
import br.com.branlycrm.pipeline.domain.SaleStatus;
import br.com.branlycrm.pipeline.dto.request.CancellationRequest;
import br.com.branlycrm.pipeline.dto.request.SaleRequest;
import br.com.branlycrm.pipeline.dto.response.SaleEventView;
import br.com.branlycrm.pipeline.dto.response.SaleView;
import br.com.branlycrm.pipeline.repositories.SaleRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Sales of the selected clinic (CRM-035). Whoever records sales registers them (SALE_REGISTER);
 * only owner and manager correct or cancel them (SALE_CORRECT); everyone who sees leads reads them.
 */
@Service
public class SaleService {
  private final TenantAccessService access;
  private final SaleRepository sales;

  public SaleService(TenantAccessService access, SaleRepository sales) {
    this.access = access;
    this.sales = sales;
  }

  /** Registers the sale and closes the lead as won. {@code version} is the lead's. */
  public SaleView register(UUID tenantId, UUID actor, UUID leadId, SaleRequest request) {
    if (request == null) throw new InvalidInputException("body");
    SaleFields fields = fields(request, false);
    long version = version(request.version());
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.SALE_REGISTER);
    return view(sales.register(clinic, leadId, version, fields));
  }

  public SaleView correct(UUID tenantId, UUID actor, UUID saleId, SaleRequest request) {
    if (request == null) throw new InvalidInputException("body");
    SaleFields fields = fields(request, true);
    long version = version(request.version());
    String reason = Sale.reason(request.reason());
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.SALE_CORRECT);
    return view(sales.correct(clinic, saleId, version, fields, reason));
  }

  public SaleView cancel(UUID tenantId, UUID actor, UUID saleId, CancellationRequest request) {
    if (request == null) throw new InvalidInputException("body");
    String reason = Sale.reason(request.reason());
    long version = version(request.version());
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.SALE_CORRECT);
    return view(sales.cancel(clinic, saleId, version, reason));
  }

  public SaleView get(UUID tenantId, UUID actor, UUID saleId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return view(sales.get(clinic, saleId));
  }

  public List<SaleEventView> history(UUID tenantId, UUID actor, UUID saleId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    return sales.history(clinic, saleId).stream().map(SaleService::view).toList();
  }

  public PageView<SaleView> list(
      UUID tenantId,
      UUID actor,
      String contactId,
      String leadId,
      String status,
      String from,
      String to,
      String page,
      String pageSize) {
    var search =
        new SaleSearch(
            uuid(contactId, "contactId", false),
            uuid(leadId, "leadId", false),
            status == null || status.isEmpty() ? null : parse(SaleStatus.class, status, "status"),
            day(from, "from"),
            day(to, "to"));
    PageQuery paging = PageQuery.of(page, pageSize);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.LEAD_READ);
    PageResult<Sale> result = sales.search(clinic, search, paging);
    return new PageView<>(
        result.items().stream().map(SaleService::view).toList(),
        paging.page(),
        paging.pageSize(),
        result.total());
  }

  private static SaleFields fields(SaleRequest request, boolean dayRequired) {
    if (request.amountCents() == null) throw new InvalidInputException("amountCents");
    LocalDate soldOn = day(request.soldOn(), "soldOn");
    if (dayRequired && soldOn == null) throw new InvalidInputException("soldOn");
    String payment = request.paymentMethod();
    return new SaleFields(
        uuid(request.procedureId(), "procedureId", true),
        uuid(request.professionalId(), "professionalId", false),
        request.amountCents(),
        soldOn,
        payment == null || payment.isEmpty()
            ? null
            : parse(PaymentMethod.class, payment, "paymentMethod"));
  }

  private static SaleView view(Sale sale) {
    SaleFields fields = sale.fields();
    return new SaleView(
        sale.id(),
        sale.leadId(),
        sale.contactId(),
        fields.procedureId(),
        fields.professionalId(),
        fields.amountCents(),
        fields.soldOn(),
        fields.paymentMethod() == null ? null : fields.paymentMethod().name(),
        sale.status().name(),
        sale.cancelReason(),
        sale.cancelledAt(),
        sale.createdAt(),
        sale.createdBy(),
        sale.version());
  }

  private static SaleEventView view(SaleEvent event) {
    SaleFields now = event.values();
    SaleFields before = event.previous();
    return new SaleEventView(
        event.id(),
        event.type().name(),
        now == null ? null : now.procedureId(),
        now == null ? null : now.professionalId(),
        now == null ? null : now.amountCents(),
        now == null ? null : now.soldOn(),
        now == null || now.paymentMethod() == null ? null : now.paymentMethod().name(),
        before == null ? null : before.procedureId(),
        before == null ? null : before.professionalId(),
        before == null ? null : before.amountCents(),
        before == null ? null : before.soldOn(),
        before == null || before.paymentMethod() == null ? null : before.paymentMethod().name(),
        event.reason(),
        event.actorId(),
        event.occurredAt());
  }

  private static long version(Long version) {
    if (version == null || version < 0) throw new InvalidInputException("version");
    return version;
  }

  private static LocalDate day(String value, String field) {
    if (value == null || value.isEmpty()) return null;
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException invalid) {
      throw new InvalidInputException(field);
    }
  }

  private static UUID uuid(String value, String field, boolean required) {
    if (value == null || value.isEmpty()) {
      if (required) throw new InvalidInputException(field);
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }
}
