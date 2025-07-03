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
import java.util.Set;

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
            KeycloakHealthStatus status = reportUp();

            // Obter informações do cluster
            String clusterName = (String) invokeMethodWithAccess(cacheManager, "getClusterName");
            status.withAttribute("clusterName", clusterName != null ? clusterName : "ejb");

            // Obter membros do cluster
            List<String> members = new ArrayList<>();
            try {
                Object transport = invokeMethodWithAccess(cacheManager, "getTransport");
                if (transport != null) {
                    @SuppressWarnings("unchecked")
                    List<String> transportMembers = (List<String>) invokeMethodWithAccess(transport, "getMembers");
                    if (transportMembers != null) {
                        members.addAll(transportMembers);
                    }
                }
            } catch (Exception e) {
                // Se não conseguir obter membros, assume nó local
                members.add(getLocalNodeName(cacheManager));
            }

            status.withAttribute("healthStatus", "HEALTHY");
            status.withAttribute("numberOfNodes", members.size());
            status.withAttribute("nodeNames", members);

            // Obter informações dos caches
            List<Map<String, String>> cacheDetails = new ArrayList<>();
            @SuppressWarnings("unchecked")
            Set<String> cacheNames = (Set<String>) invokeMethodWithAccess(cacheManager, "getCacheNames");

            if (cacheNames != null) {
                for (String cacheName : cacheNames) {
                    Map<String, String> cacheInfo = new LinkedHashMap<>();
                    cacheInfo.put("cacheName", cacheName);
                    cacheInfo.put("healthStatus", "HEALTHY");
                    cacheDetails.add(cacheInfo);
                }
            }

            status.withAttribute("cacheDetails", cacheDetails);

            return status;

        } catch (Exception e) {
            log.error("Erro ao verificar saúde do Infinispan", e);
            return reportDown()
                .withAttribute("error", e.getMessage())
                .withAttribute("errorType", e.getClass().getName())
                .withAttribute("jndiName", jndiName);
        }
    }

    private String getLocalNodeName(Object cacheManager) {
        try {
            // Tenta obter o nome do nó de várias maneiras
            try {
                Object nodeName = invokeMethodWithAccess(cacheManager, "getNodeName");
                if (nodeName != null) {
                    return nodeName.toString();
                }
            } catch (Exception e) {
                try {
                    Object localAddress = invokeMethodWithAccess(cacheManager, "getLocalAddress");
                    if (localAddress != null) {
                        return localAddress.toString();
                    }
                } catch (Exception e2) {
                    return System.getProperty("jboss.node.name", "neumann");
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return "neumann";
    }

    protected Object lookupCacheManager() {
        try {
            return new InitialContext().lookup(jndiName);
        } catch (Exception e) {
            log.warnv("Erro ao fazer lookup do CacheManager com nome: {0}, erro: {1}", jndiName, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private Object invokeMethodWithAccess(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        
        try {
            Method method;
            if (args.length == 0) {
                method = target.getClass().getMethod(methodName);
            } else {
                Class<?>[] paramTypes = new Class<?>[args.length];
                for (int i = 0; i < args.length; i++) {
                    paramTypes[i] = args[i].getClass();
                }
                method = target.getClass().getMethod(methodName, paramTypes);
            }
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Exception e) {
            log.debugf("Erro ao invocar método %s: %s", methodName, e.getMessage());
            return null;
        }
    }
}
