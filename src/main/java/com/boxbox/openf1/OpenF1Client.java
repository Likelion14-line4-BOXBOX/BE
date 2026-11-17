package com.boxbox.openf1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenF1Client {
    private final RestClient restClient;

    public OpenF1Client(RestClient.Builder builder) {
        builder.baseUrl("https://api.openf1.org");
        this.restClient = builder.build();
    }

    public String getMeetings(int year){
        return restClient.get().uri(uriBuilder -> uriBuilder
                .path("/v1/meetings")
                .queryParam("year", year)
                .build()).retrieve().body(String.class);
    }

    public List<Session> getSessions(int year) {
        return restClient.get().uri(uriBuilder -> uriBuilder
                .path("/v1/sessions")
                .queryParam("year", year)
                .build()).retrieve().requiredBody(new ParameterizedTypeReference<List<Session>>() {});
    }

    public List<SessionResult> getSessionResults(long sessionKey) {
        return restClient.get().uri(uriBuilder -> uriBuilder
                .path("/v1/session_result")
                .queryParam("session_key", sessionKey)
                .build()).retrieve().requiredBody(new ParameterizedTypeReference<List<SessionResult>>() {});
    }

    public List<SessionDriver> getSessionDrivers(long sessionKey) {
        return restClient.get().uri(uriBuilder -> uriBuilder
                .path("/v1/drivers")
                .queryParam("session_key", sessionKey)
                .build()).retrieve().requiredBody(new ParameterizedTypeReference<List<SessionDriver>>() {});
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionDriver(@JsonProperty("driver_number") int driverNumber) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(
            @JsonProperty("meeting_key") Long meetingKey,
            @JsonProperty("session_key") Long sessionKey,
            @JsonProperty("session_name") String sessionName,
            @JsonProperty("session_type") String sessionType,
            @JsonProperty("date_start") OffsetDateTime dateStart,
            @JsonProperty("date_end") OffsetDateTime dateEnd,
            @JsonProperty("is_cancelled") Boolean cancelled
    ) {
        public boolean isMainRace() {
            return "Race".equals(sessionName) && "Race".equals(sessionType) && !Boolean.TRUE.equals(cancelled);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionResult(
            @JsonProperty("driver_number") Integer driverNumber,
            Integer position,
            Boolean dnf,
            Boolean dns,
            Boolean dsq
    ) {}
}
