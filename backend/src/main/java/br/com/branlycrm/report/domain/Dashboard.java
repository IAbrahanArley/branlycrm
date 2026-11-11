package br.com.branlycrm.report.domain;

import java.time.LocalDate;

/** The reception dashboard of a clinic (docs/metricas.md): today in the clinic's time zone. */
public record Dashboard(
    LocalDate today,
    String timeZone,
    long leadsToday,
    long salesTodayCount,
    long salesTodayCents,
    long salesMonthCount,
    long salesMonthCents,
    long tasksOverdue,
    long tasksToday) {}
