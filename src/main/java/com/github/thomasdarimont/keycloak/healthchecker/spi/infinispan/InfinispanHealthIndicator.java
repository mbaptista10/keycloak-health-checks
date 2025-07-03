package com.github.thomasdarimont.keycloak.healthchecker.spi.infinispan;

import com.github.thomasdarimont.keycloak.healthchecker.model.HealthStatus;
import com.github.thomasdarimont.keycloak.healthchecker.model.KeycloakHealthStatus;
import com.github.thomasdarimont.keycloak.healthchecker.spi.AbstractHealthIndicator;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;

import javax.naming.InitialContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JBossLog
public class InfinispanHealthIndicator extends AbstractHealthIndicator {

    public static final String KEYCLOAK_CACHE_MANAGER_JNDI_NAME = "java:jboss/infinispan/container/keycloak";

    protected final String jndiName;

    public InfinispanHealthIndicator(Config.Scope config) {
        super("infinispan");
        this.jndiName = config.get("jndiName", KEYCLOAK_CACHE_MANAGER_JNDI_NAME);
    }

    @Override
    public HealthStatus check() {
        try {
            Object cacheManager = lookupCacheManager();
            
            // Verificação simplificada
            try {
                // Apenas verifica se conseguimos acessar o cache manager
                return reportUp()
                    .withAttribute("status", "Cache manager disponível")
                    .withAttribute("jndiName", jndiName);
            } catch (Exception e) {
                log.error("Erro ao verificar status do cache manager", e);
                return reportDown()
                    .withAttribute("error", e.getMessage())
                    .withAttribute("errorType", e.getClass().getName())
                    .withAttribute("jndiName", jndiName);
            }
        } catch (Exception e) {
            log.error("Erro ao fazer lookup do cache manager", e);
            return reportDown()
                .withAttribute("error", e.getMessage())
                .withAttribute("errorType", e.getClass().getName())
                .withAttribute("jndiName", jndiName);
        }
    }

    protected Object lookupCacheManager() {
        try {
            return new InitialContext().lookup(jndiName);
        } catch (Exception e) {
            log.warnv("Erro ao fazer lookup do CacheManager com nome: {0}, erro: {1}", jndiName, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
