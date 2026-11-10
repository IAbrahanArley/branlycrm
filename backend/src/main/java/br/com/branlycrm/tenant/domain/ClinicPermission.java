package br.com.branlycrm.tenant.domain;

/** Actions of the permission matrix in docs/permissoes.md (ADR-025), one per row. */
public enum ClinicPermission {
  CLINIC_SETTINGS_VIEW,
  CLINIC_SETTINGS_EDIT,
  SUBSCRIPTION_VIEW,
  TEAM_VIEW,
  TEAM_MANAGE,
  /** Funnel, stages, loss reasons, procedures, professionals and quick replies. */
  COMMERCIAL_SETTINGS_EDIT,
  CHANNEL_VIEW,
  CONTACT_READ,
  /** Create, edit, tags and consent. */
  CONTACT_WRITE,
  CONTACT_ARCHIVE,
  /** Rename or delete a tag of the clinic catalog (deleting removes it from every contact). */
  TAG_MANAGE,
  CONTACT_IMPORT,
  /** Kanban, list and timeline. */
  LEAD_READ,
  /** Create, move stage, temperature and notes. */
  LEAD_WRITE,
  /** Become the responsible: {@link AccessScope#OWN} means only for oneself. */
  LEAD_TAKE,
  LEAD_ASSIGN,
  LEAD_ORIGIN_EDIT,
  /** Register a sale or a loss. */
  SALE_REGISTER,
  SALE_CORRECT,
  INBOX_READ,
  /** Reply, take the conversation, send media and templates. */
  INBOX_REPLY,
  /** Create and assign tasks. */
  TASK_WRITE,
  TASK_READ,
  /** One's own notifications; ownership is enforced by the notifications module. */
  NOTIFICATIONS_VIEW,
  LEAD_LIST_VIEW,
  DATA_EXPORT,
  RECEPTION_DASHBOARD_VIEW,
  MONTHLY_REPORT_VIEW,
  AUDIT_VIEW
}
