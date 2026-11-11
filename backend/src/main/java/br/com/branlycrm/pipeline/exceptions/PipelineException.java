package br.com.branlycrm.pipeline.exceptions;

/**
 * Funnel and loss reason configuration outcome (CRM-036); the code is the reason's name. HTTP
 * translation belongs to the controller advice.
 */
public final class PipelineException extends RuntimeException {
  public enum Reason {
    /** The funnel changed since it was read (its version). */
    PIPELINE_STALE,
    /** Missing in this clinic's funnel, or archived where an active one is needed. */
    STAGE_NOT_FOUND,
    /** Another active stage has the same name, whatever the case. */
    STAGE_DUPLICATE,
    /** The funnel already has 12 active open stages. */
    STAGE_LIMIT,
    /** Won, lost and entry stages are never archived. */
    STAGE_PROTECTED,
    /** An open lead is still in the stage. */
    STAGE_HAS_OPEN_LEADS,
    /** The order sent is not exactly the funnel's active open stages. */
    STAGE_ORDER_MISMATCH,
    LOSS_REASON_NOT_FOUND,
    /** Another reason, archived ones included, has the same name. */
    LOSS_REASON_DUPLICATE,
    LOSS_REASON_STALE,
    /** The clinic already has 50 loss reasons. */
    LOSS_REASON_LIMIT,
    /** At least one reason stays active, so a loss can always be recorded. */
    LOSS_REASON_LAST_ACTIVE
  }

  private final Reason reason;

  public PipelineException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
