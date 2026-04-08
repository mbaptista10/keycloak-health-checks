package com.github.thomasdarimont.keycloak.healthchecker.spi.infinispan;

import com.github.thomasdarimont.keycloak.healthchecker.model.HealthStatus;
import com.github.thomasdarimont.keycloak.healthchecker.model.KeycloakHealthStatus;
import com.github.thomasdarimont.keycloak.healthchecker.spi.AbstractHealthIndicator;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;

import javax.naming.InitialContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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

            String clusterName = (String) invokeMethodWithAccess(cacheManager, "getClusterName");
            status.withAttribute("clusterName", clusterName != null ? clusterName : "ejb");

            String healthStatus = getClusterHealthStatus(cacheManager);
            boolean rebalancing = "HEALTHY_REBALANCING".equals(healthStatus);
            status.withAttribute("healthStatus", healthStatus);
            status.withAttribute("rebalancingInProgress", rebalancing);

            String coordinatorName = null;
            String localNodeName = getLocalNodeName(cacheManager);
            List<Map<String, Object>> nodes = new ArrayList<>();

            Object transport = invokeMethodWithAccess(cacheManager, "getTransport");
            if (transport != null) {
                Object coordinator = invokeMethodWithAccess(transport, "getCoordinator");
                coordinatorName = coordinator != null ? coordinator.toString() : null;

                @SuppressWarnings("unchecked")
                List<?> members = (List<?>) invokeMethodWithAccess(transport, "getMembers");
                if (members != null && !members.isEmpty()) {
                    Map<String, String> physicalAddresses = resolvePhysicalAddresses(transport, members);

                    for (Object member : members) {
                        Map<String, Object> nodeInfo = new LinkedHashMap<>();
                        String memberName = member.toString();
                        nodeInfo.put("name", memberName);

                        String physAddr = physicalAddresses.get(memberName);
                        if (physAddr != null) {
                            nodeInfo.put("physicalAddress", physAddr);
                        }

                        nodeInfo.put("isCoordinator", memberName.equals(coordinatorName));
                        nodes.add(nodeInfo);
                    }
                }
            }

            if (nodes.isEmpty()) {
                Map<String, Object> nodeInfo = new LinkedHashMap<>();
                nodeInfo.put("name", localNodeName);
                nodeInfo.put("isCoordinator", true);
                nodes.add(nodeInfo);
                coordinatorName = localNodeName;
            }

            status.withAttribute("numberOfNodes", nodes.size());
            status.withAttribute("coordinator", coordinatorName);
            status.withAttribute("localNode", localNodeName);
            status.withAttribute("nodes", nodes);

            Map<String, String> cacheHealthMap = getCacheHealthStatuses(cacheManager);
            List<Map<String, Object>> cacheDetails = buildCacheDetails(cacheManager, cacheHealthMap);
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

    private String getClusterHealthStatus(Object cacheManager) {
        try {
            Object health = invokeMethodWithAccess(cacheManager, "getHealth");
            if (health != null) {
                Object clusterHealth = invokeMethodWithAccess(health, "getClusterHealth");
                if (clusterHealth != null) {
                    Object hs = invokeMethodWithAccess(clusterHealth, "getHealthStatus");
                    if (hs != null) {
                        return hs.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.debugf("Não foi possível obter health status do cluster Infinispan: %s", e.getMessage());
        }
        return "HEALTHY";
    }

    private Map<String, String> getCacheHealthStatuses(Object cacheManager) {
        Map<String, String> cacheHealthMap = new LinkedHashMap<>();
        try {
            Object health = invokeMethodWithAccess(cacheManager, "getHealth");
            if (health != null) {
                List<?> cacheHealthList = (List<?>) invokeMethodWithAccess(health, "getCacheHealth");
                if (cacheHealthList != null) {
                    for (Object ch : cacheHealthList) {
                        String name = (String) invokeMethodWithAccess(ch, "getCacheName");
                        Object hs = invokeMethodWithAccess(ch, "getStatus");
                        if (name != null && hs != null) {
                            cacheHealthMap.put(name, hs.toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debugf("Não foi possível obter health status por cache: %s", e.getMessage());
        }
        return cacheHealthMap;
    }

    private List<Map<String, Object>> buildCacheDetails(Object cacheManager, Map<String, String> cacheHealthMap) {
        List<Map<String, Object>> cacheDetails = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Set<String> cacheNames = (Set<String>) invokeMethodWithAccess(cacheManager, "getCacheNames");
        if (cacheNames == null) {
            return cacheDetails;
        }

        for (String cacheName : cacheNames) {
            Map<String, Object> cacheInfo = new LinkedHashMap<>();
            cacheInfo.put("cacheName", cacheName);
            cacheInfo.put("healthStatus", cacheHealthMap.getOrDefault(cacheName, "HEALTHY"));

            Object cache = invokeMethodWithAccess(cacheManager, "getCache", cacheName);
            if (cache != null) {
                Object cacheConfig = invokeMethodWithAccess(cache, "getCacheConfiguration");
                if (cacheConfig != null) {
                    Object clustering = invokeMethodWithAccess(cacheConfig, "clustering");
                    if (clustering != null) {
                        Object cacheMode = invokeMethodWithAccess(clustering, "cacheMode");
                        if (cacheMode != null) {
                            cacheInfo.put("cacheMode", cacheMode.toString());
                        }
                    }
                }

                Object cacheStatus = invokeMethodWithAccess(cache, "getStatus");
                if (cacheStatus != null) {
                    cacheInfo.put("cacheStatus", cacheStatus.toString());
                }

                Object size = invokeMethodWithAccess(cache, "size");
                cacheInfo.put("size", size != null ? size : 0);

                Object advancedCache = invokeMethodWithAccess(cache, "getAdvancedCache");
                if (advancedCache != null) {
                    Object stats = invokeMethodWithAccess(advancedCache, "getStats");
                    if (stats != null) {
                        Object evictions = invokeMethodWithAccess(stats, "getEvictions");
                        cacheInfo.put("evictions", evictions != null ? evictions : 0L);
                    }
                }
            }

            cacheDetails.add(cacheInfo);
        }

        return cacheDetails;
    }

    private Map<String, String> resolvePhysicalAddresses(Object transport, List<?> members) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Object channel = invokeMethodWithAccess(transport, "getChannel");
            if (channel == null) {
                return result;
            }

            Class<?> eventClass = Class.forName("org.jgroups.Event");
            Field getPhysAddrField = eventClass.getField("GET_PHYSICAL_ADDRESS");
            int getPhysAddr = getPhysAddrField.getInt(null);
            Constructor<?> eventCtor = eventClass.getConstructor(int.class, Object.class);

            for (Object member : members) {
                try {
                    Object event = eventCtor.newInstance(getPhysAddr, member);
                    Object physAddr = invokeMethodWithAccess(channel, "down", event);
                    if (physAddr != null) {
                        result.put(member.toString(), physAddr.toString());
                    }
                } catch (Exception e) {
                    log.debugf("Não foi possível resolver endereço físico para membro %s: %s", member, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debugf("Não foi possível resolver endereços físicos via JGroups: %s", e.getMessage());
        }
        return result;
    }

    private String getLocalNodeName(Object cacheManager) {
        try {
            Object transport = invokeMethodWithAccess(cacheManager, "getTransport");
            if (transport != null) {
                Object address = invokeMethodWithAccess(transport, "getAddress");
                if (address != null) {
                    return address.toString();
                }
            }

            Object nodeName = invokeMethodWithAccess(cacheManager, "getNodeName");
            if (nodeName != null) {
                return nodeName.toString();
            }

            Object localAddress = invokeMethodWithAccess(cacheManager, "getLocalAddress");
            if (localAddress != null) {
                return localAddress.toString();
            }
        } catch (Exception e) {
            // fallback
        }
        return System.getProperty("jboss.node.name", "unknown");
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
