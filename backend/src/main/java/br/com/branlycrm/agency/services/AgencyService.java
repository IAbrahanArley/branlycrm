package br.com.branlycrm.agency.services;

import br.com.branlycrm.agency.domain.PortfolioClinic;
import br.com.branlycrm.agency.dto.response.AgencyView;
import br.com.branlycrm.agency.dto.response.PortfolioClinicView;
import br.com.branlycrm.agency.exceptions.AgencyAccessException;
import br.com.branlycrm.agency.repositories.AgencyRepository;
import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.dto.response.PageView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agency authority (ADR-026): the agency comes from the subject's active admin membership; the
 * agency identifier from the client is only a selector. An agency the subject does not administer,
 * a suspended one and a missing one answer alike.
 */
@Service
public class AgencyService {
  private final AgencyRepository agencies;

  public AgencyService(AgencyRepository agencies) {
    this.agencies = agencies;
  }

  @Transactional(readOnly = true)
  public List<AgencyView> list(UUID subjectId) {
    return agencies.listForAdmin(subjectId).stream()
        .map(agency -> new AgencyView(agency.agencyId(), agency.name()))
        .toList();
  }

  /** One snapshot for count and items. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PageView<PortfolioClinicView> portfolio(UUID agencyId, UUID subjectId, PageQuery page) {
    requireAdmin(agencyId, subjectId);
    PageResult<PortfolioClinic> result = agencies.portfolio(agencyId, page);
    return new PageView<>(
        result.items().stream()
            .map(
                clinic ->
                    new PortfolioClinicView(
                        clinic.tenantId(),
                        clinic.clinicName(),
                        clinic.status(),
                        clinic.ownerInvitation(),
                        clinic.createdAt()))
            .toList(),
        page.page(),
        page.pageSize(),
        result.total());
  }

  /** For other modules acting on the portfolio (onboarding); joins the caller's transaction. */
  @Transactional(readOnly = true)
  public void requireAdmin(UUID agencyId, UUID subjectId) {
    if (agencyId == null || !agencies.isActiveAdmin(agencyId, subjectId)) {
      throw new AgencyAccessException();
    }
  }
}
