package com.github.thomasdarimont.keycloak.healthchecker.spi.infinispan;

import com.github.thomasdarimont.keycloak.healthchecker.model.HealthStatus;
import com.github.thomasdarimont.keycloak.healthchecker.model.KeycloakHealthStatus;
import com.github.thomasdarimont.keycloak.healthchecker.spi.AbstractHealthIndicator;
import lombok.extern.jbosslog.JBossLog;
import org.infinispan.health.ClusterHealth;
import org.infinispan.health.Health;
import org.infinispan.manager.EmbeddedCacheManager;
import org.keycloak.Config;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

        Health infinispanHealth = getInfinispanHealth();
        ClusterHealth clusterHealth = infinispanHealth.getClusterHealth();

        KeycloakHealthStatus status = determineClusterHealth(clusterHealth);

        List<Map<Object, Object>> detailedCacheHealthInfo = infinispanHealth.getCacheHealth().stream().map(c -> {
            Map<Object, Object> item = new LinkedHashMap<>();
            item.put("cacheName", c.getCacheName());
            item.put("healthStatus", c.getStatus());
            return item;
        }).collect(Collectors.toList());

        status//
                .withAttribute("hostInfo",  infinispanHealth.getHostInfo())
                .withAttribute("clusterName", clusterHealth.getClusterName()) //
                .withAttribute("healthStatus", clusterHealth.getHealthStatus()) //
                .withAttribute("numberOfNodes", clusterHealth.getNumberOfNodes()) //
                .withAttribute("nodeNames", clusterHealth.getNodeNames())
                .withAttribute("cacheDetails", detailedCacheHealthInfo)
        ;

        return status;
    }

    protected Health getInfinispanHealth() {
        Object cacheManager = lookupCacheManager();
        try {
            Method healthMethod = cacheManager.getClass().getMethod("getHealth");
            return (Health) healthMethod.invoke(cacheManager);
        } catch (Exception e) {
            log.error("Erro ao acessar método getHealth via reflexão", e);
            throw new RuntimeException(e);
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

    protected KeycloakHealthStatus determineClusterHealth(ClusterHealth clusterHealth) {

        switch (clusterHealth.getHealthStatus()) {
            case HEALTHY:
                return reportUp();
            case HEALTHY_REBALANCING:
                return reportUp();
            case DEGRADED:
            case FAILED:
                return reportDown();
            default:
                return reportDown();
        }
    }
}
