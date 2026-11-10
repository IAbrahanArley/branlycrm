package br.com.branlycrm.pipeline.domain;

/** A lead in lists and the Kanban, with the contact's name and phone (never notes). */
public record LeadSummary(Lead lead, String contactName, String contactPhone) {
  public LeadSummary {
    if (lead == null) throw new IllegalArgumentException("Incomplete lead summary");
  }

  @Override
  public String toString() {
    return "LeadSummary[id=" + lead.id() + "]";
  }
}
