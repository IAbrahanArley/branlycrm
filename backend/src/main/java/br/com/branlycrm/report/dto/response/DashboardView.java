package br.com.branlycrm.report.dto.response;

import java.time.LocalDate;

/**
 * The reception dashboard (docs/metricas.md): {@code today} in the clinic's {@code timeZone}; sale
 * amounts in cents.
 *
 * <p>{@code conversationsAwaitingReply}: WhatsApp conversations whose latest message came from the
 * contact; {@code conversationsUnassigned}: those of them nobody has taken (CRM-083).
 */
public record DashboardView(
    LocalDate today,
    String timeZone,
    long leadsToday,
    long salesTodayCount,
    long salesTodayCents,
    long salesMonthCount,
    long salesMonthCents,
    long tasksOverdue,
    long tasksToday,
    long conversationsAwaitingReply,
    long conversationsUnassigned) {}
