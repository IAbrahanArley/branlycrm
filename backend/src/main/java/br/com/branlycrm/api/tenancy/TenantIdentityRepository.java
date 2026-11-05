package br.com.branlycrm.api.tenancy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class TenantIdentityRepository {
    public void verify(Connection connection, UUID tenantId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT tenant_id FROM tenant_identity WHERE singleton")) {
            statement.setQueryTimeout(5);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !tenantId.equals(rows.getObject(1, UUID.class)) || rows.next()) {
                    throw new SQLException("Tenant database identity mismatch");
                }
            }
        }
    }
}