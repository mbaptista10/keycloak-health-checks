package com.github.thomasdarimont.keycloak.healthchecker.rest;

import com.github.thomasdarimont.keycloak.healthchecker.model.AggregatedHealthStatus;
import com.github.thomasdarimont.keycloak.healthchecker.model.HealthState;
import com.github.thomasdarimont.keycloak.healthchecker.model.HealthStatus;
import com.github.thomasdarimont.keycloak.healthchecker.spi.GuardedHealthIndicator;
import com.github.thomasdarimont.keycloak.healthchecker.spi.HealthIndicator;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.List;

@JBossLog
public class HealthCheckResource {

    public static final Response NOT_FOUND = Response.status(Response.Status.NOT_FOUND).build();

    private static final Comparator<HealthIndicator> HEALTH_INDICATOR_COMPARATOR = Comparator.comparing(
            HealthIndicator::getName,
            Comparator.naturalOrder());

    protected final KeycloakSession session;

    public HealthCheckResource(KeycloakSession session) {
        this.session = session;
    }

    @GET
    @Path("check")
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkHealth() {
        Set<HealthIndicator> checks = new TreeSet<>(HEALTH_INDICATOR_COMPARATOR);
        checks.addAll(this.session.getAllProviders(HealthIndicator.class));

        Optional<HealthStatus> healthStatus = aggregatedHealthStatusFrom(checks);
        
        healthStatus.ifPresent(status -> {
            String statusSummary = buildStatusSummary(status);
            if (status.isUp()) {
                log.infof("Health check realizado: %s", statusSummary);
            } else {
                log.warnf("Health check realizado: %s", statusSummary);
            }
        });

        return healthStatus
                .map(this::toHealthResponse)
                .orElse(NOT_FOUND);
    }

    private String buildStatusSummary(HealthStatus status) {
        Map<String, Object> details = status.getDetails();
        List<String> statusList = new ArrayList<>();
        
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String indicatorName = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> indicatorDetails = (Map<String, Object>) entry.getValue();
            HealthState state = (HealthState) indicatorDetails.get("state");
            String stateStr = state == HealthState.UP ? "HEALTHY" : "UNHEALTHY";
            statusList.add(String.format("%s: %s", 
                capitalizeFirstLetter(indicatorName), 
                stateStr));
        }
        
        return String.join(", ", statusList);
    }

    private String capitalizeFirstLetter(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }

    @GET
    @Path("check/{indicator}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkHealthFor(@PathParam("indicator") String name) {
        Optional<HealthStatus> healthStatus = tryFindFirstHealthIndicatorWithName(name)
                .map(GuardedHealthIndicator::new)
                .map(HealthIndicator::check);

        healthStatus.ifPresent(status -> {
            if (status.isUp()) {
                log.infof("Health check do indicador %s realizado: HEALTHY", name);
            } else {
                log.warnf("Health check do indicador %s realizado: UNHEALTHY - Detalhes: %s", name, status.getDetails());
            }
        });

        return healthStatus
                .map(this::toHealthResponse)
                .orElse(NOT_FOUND);
    }

    protected Optional<HealthStatus> aggregatedHealthStatusFrom(Set<HealthIndicator> healthIndicators) {

        return healthIndicators.stream() //
                .map(GuardedHealthIndicator::new) //
                .filter(HealthIndicator::isApplicable) // only show relevant health indicators
                .map(HealthIndicator::check) //
                .reduce(this::combineHealthStatus); //
    }

    protected Response toHealthResponse(HealthStatus health) {

        if (health.isUp()) {
            return Response.ok(health).build();
        }

        return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(health).build();
    }

    protected Optional<HealthIndicator> tryFindFirstHealthIndicatorWithName(String healthIndicatorName) {

        Set<HealthIndicator> allProviders = this.session.getAllProviders(HealthIndicator.class);
        return allProviders.stream().filter(i -> i.getName().equals(healthIndicatorName)).findFirst();
    }

    protected HealthStatus combineHealthStatus(HealthStatus first, HealthStatus second) {

        if (!(first instanceof AggregatedHealthStatus)) {

            AggregatedHealthStatus healthStatus = new AggregatedHealthStatus();
            healthStatus.addHealthInfo(first);
            healthStatus.addHealthInfo(second);

            return healthStatus;
        }

        AggregatedHealthStatus accumulator = (AggregatedHealthStatus) first;
        accumulator.addHealthInfo(second);

        return accumulator;
    }
}
