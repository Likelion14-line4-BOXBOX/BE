package com.boxbox.garage.overview;

import static org.assertj.core.api.Assertions.*;

import com.boxbox.garage.overview.dto.GarageProfile;
import com.boxbox.garage.profile.ProfileRepository;
import com.boxbox.garage.stamp.RaceStampService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:boxbox-garage-foundation-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.schema-locations=classpath:db/test-schema.sql,classpath:db/chat-schema.sql,classpath:db/chat-write-schema.sql"})
@ActiveProfiles("test")
@Sql({"/db/garage-cleanup.sql", "/db/profile-test-data.sql", "/db/garage-test-data.sql"})
class GarageFoundationTests {
    @Autowired ProfileRepository profiles;
    @Autowired RaceStampService stamps;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @Test void publicProfileOmitsOwnerSettingAndPrivateMemberFieldsButKeepsNullableCarNumber() {
        var profile = profiles.findGarageProfile(1).orElseThrow();
        var data = serialized(profile, false);
        assertThat(data).containsOnlyKeys("userId", "nickname", "bio", "avatarAssetId", "favoriteTeam", "favoriteDriver");
        assertThat(data.get("userId")).isEqualTo("1");
        assertThat(((Map<?, ?>) data.get("favoriteDriver")).containsKey("number")).isTrue();
        assertThat(((Map<?, ?>) data.get("favoriteDriver")).get("number")).isNull();
        assertThat(((Map<?, ?>) data.get("favoriteDriver")).get("driverId")).isEqualTo("11");
    }

    @ParameterizedTest @ValueSource(longs = {1, 2})
    void ownerSettingRemainsBooleanIncludingFalse(long target) {
        var profile = profiles.findGarageProfile(target).orElseThrow();
        assertThat(serialized(profile, true)).containsEntry("showMarketAssets", profile.showMarketAssets());
        assertThat(serialized(profile, false)).doesNotContainKey("showMarketAssets");
    }

    @Test void unsetFavoritesAreExplicitNullAndPresetRemainsDefault() {
        var data = serialized(profiles.findGarageProfile(2).orElseThrow(), false);
        assertThat(data).containsEntry("favoriteTeam", null).containsEntry("favoriteDriver", null)
                .containsEntry("avatarAssetId", "avatar_default");
    }

    @Test void targetLookupMatchesGarageReadStatusAndDoesNotChangeOwnProfileAuthentication() {
        assertThat(profiles.findGarageProfile(3)).isPresent();
        assertThat(profiles.findGarageProfile(4)).isEmpty();
        assertThat(profiles.findGarageProfile(5)).isEmpty();
        assertThat(profiles.findGarageProfile(999)).isEmpty();
        assertThat(profiles.findActiveProfile("suspended")).isEmpty();
    }

    @Test void profileAndStoredStampReadsHaveNoSideEffectsAndExcludeMessageOnlyAndOtherSeason() {
        var users = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        var participants = jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id");
        profiles.findGarageProfile(1).orElseThrow();
        var summary = stamps.list("other", "1", Map.of());
        assertThat(summary.season()).isEqualTo(2026);
        assertThat(summary.watchedCount()).isEqualTo(1);
        assertThat(summary.totalRaces()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(users);
        assertThat(jdbc.queryForList("SELECT * FROM chat_participants ORDER BY room_id,user_id")).isEqualTo(participants);
    }

    private Map<String, Object> serialized(com.boxbox.garage.profile.dto.ProfileResponse profile, boolean owner) {
        return JsonParserFactory.getJsonParser().parseMap(json.writeValueAsString(GarageProfile.from(profile, owner)));
    }
}
