package br.com.branlycrm.api.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class TenantConnectionService {
    private final TenantAccessService access;
    private final Environment environment;
    private final TenantIdentityRepository identities;
    private final String host;
    private final int port;

    public TenantConnectionService(TenantAccessService access, Environment environment, TenantIdentityRepository identities,
            @Value("${app.tenancy.host:localhost}") String host,
            @Value("${app.tenancy.port:5432}") int port) {
        if (!host.matches("[a-zA-Z0-9.-]+") || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid tenant database endpoint");
        }
        this.access = access;
        this.identities = identities;
        this.environment = environment;
        this.host = host;
        this.port = port;
    }

    // Explicit tenant parameter avoids thread-local leakage in requests and workers.
    // Caller owns the returned connection and must close it with try-with-resources.
    public Connection open(UUID tenantId, UUID authenticatedSubject) throws SQLException {
        var database = access.authorize(tenantId, authenticatedSubject);
        String password = environment.getProperty(database.credentialReference());
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Tenant credentials unavailable");
        }
        Properties properties = new Properties();
        properties.setProperty("user", database.databaseUser());
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5");
        Connection connection = DriverManager.getConnection(
            "jdbc:postgresql://" + host + ":" + port + "/" + database.databaseName(), properties);
        try {
            identities.verify(connection, tenantId);
            return connection;
        } catch (SQLException | RuntimeException error) {
            try { connection.close(); } catch (SQLException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }
}