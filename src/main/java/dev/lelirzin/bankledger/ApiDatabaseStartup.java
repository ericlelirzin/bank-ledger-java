package dev.lelirzin.bankledger;

import java.io.IOException;
import java.sql.SQLException;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class ApiDatabaseStartup implements ServletContextInitializer {

    private final MigrationRunner migrations;

    ApiDatabaseStartup(MigrationRunner migrations) {
        this.migrations = migrations;
    }

    @Override
    public void onStartup(ServletContext context) throws ServletException {
        try {
            migrations.apply();
        } catch (IOException | SQLException failure) {
            throw new ServletException("Could not apply ledger migrations", failure);
        }
    }
}
