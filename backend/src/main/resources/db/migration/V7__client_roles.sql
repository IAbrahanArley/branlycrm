-- ADR-025: the client's role catalog. Gestor = OWNER or MANAGER, Atendente = ATTENDANT,
-- Somente leitura = VIEWER. RECEPTION and SALES merge into ATTENDANT; PROFESSIONAL, which only saw
-- its own agenda (now out of the MVP), becomes the least-privileged VIEWER.
-- Same order as V6: the old CHECK is dropped before converting, and no IF EXISTS, so an unexpected
-- constraint name fails the migration instead of leaving two CHECKs active.
-- OWNER rows are not touched, so the last-owner trigger from V6 never fires here.
ALTER TABLE tenant_memberships DROP CONSTRAINT tenant_memberships_role_check;
UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE role IN ('RECEPTION', 'SALES');
UPDATE tenant_memberships SET role = 'VIEWER' WHERE role = 'PROFESSIONAL';
ALTER TABLE tenant_memberships ADD CONSTRAINT tenant_memberships_role_check
    CHECK (role IN ('OWNER', 'MANAGER', 'ATTENDANT', 'VIEWER'));
