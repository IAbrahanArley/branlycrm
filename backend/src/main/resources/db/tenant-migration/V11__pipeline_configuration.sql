-- Configuration writes of the funnel and loss reasons (CRM-036, ADR-033). Applied by the
-- provisioner, or by the tenant-migrate rollout, as the clinic owner role. The runtime creates,
-- renames, reorders and archives; it never deletes, never changes a stage's kind, key or funnel,
-- and never rewrites authorship of creation.

GRANT INSERT ON pipeline_stages, loss_reasons TO ${runtimeRole};
GRANT UPDATE (name, position, updated_at, updated_by, archived_at, archived_by, version)
    ON pipeline_stages TO ${runtimeRole};
GRANT UPDATE (name, updated_at, updated_by, archived_at, archived_by, version)
    ON loss_reasons TO ${runtimeRole};
-- The funnel row is locked and versioned by every stage change; nothing else of it changes.
GRANT UPDATE (updated_at, updated_by, version) ON pipelines TO ${runtimeRole};
