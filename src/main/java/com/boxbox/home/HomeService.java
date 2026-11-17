package com.boxbox.home;

import com.boxbox.market.portfolio.MarketPortfolioService;
import com.boxbox.market.round.MarketRoundService;
import com.boxbox.radio.chat.ChatService;
import com.boxbox.radio.post.PostSummaryReader;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class HomeService {
    private final HomeRepository repository;
    private final PostSummaryReader posts;
    private final ChatService chats;
    private final MarketRoundService rounds;
    private final MarketPortfolioService portfolios;
    private final int season;

    HomeService(HomeRepository repository, PostSummaryReader posts, ChatService chats, MarketRoundService rounds,
                MarketPortfolioService portfolios, @Value("${boxbox.market.preparation.season:2026}") int season) {
        this.repository = repository;
        this.posts = posts;
        this.chats = chats;
        this.rounds = rounds;
        this.portfolios = portfolios;
        this.season = season;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HomeResponse home(String loginId, Instant now) {
        Long viewer = loginId == null ? null : repository.viewer(loginId);
        var race = repository.nextRace(season, now);
        var session = race == null ? null : repository.nextSession(race.raceId(), now);
        var room = chatRoom();
        var round = rounds.current(now).round();
        var marketRound = round == null ? null : new HomeResponse.MarketRound(round.roundId(), round.raceId(),
                round.season(), round.roundNumber(), round.raceName(), round.status(), round.openAt(), round.closeAt(),
                round.publishAt(), round.rankingPublishedAt(), round.ruleVersion(), round.statusVersion());
        var selected = repository.latestPosts();
        var boards = selected.stream().collect(Collectors.toMap(
                post -> Long.toString(post.id()), Function.identity()));
        var featured = posts.findAll(selected.stream().map(HomeRepository.Featured::id).toList(), viewer).stream()
                .map(post -> {
                    var board = boards.get(post.postId());
                    return HomeResponse.FeaturedPost.from(post, board.boardName(), board.boardType());
                }).toList();
        var portfolio = viewer == null ? null : portfolios.portfolio(loginId, round == null ? null : round.roundId(), now);
        var marketSummary = portfolio == null ? null : new HomeResponse.MarketSummary(portfolio.totalAssets(),
                portfolio.seasonReturnRate(), portfolio.seasonRank(), portfolio.snapshotAt());
        return new HomeResponse(race, session, room, marketRound, featured, marketSummary);
    }

    private HomeResponse.ChatRoom chatRoom() {
        String seasonValue = Integer.toString(season);
        var rooms = chats.rooms(Map.of("status", "LIVE", "size", "1", "season", seasonValue), null).items();
        if (rooms.isEmpty()) {
            rooms = chats.rooms(Map.of("status", "SCHEDULED", "size", "1", "season", seasonValue), null).items();
        }
        return rooms.stream().findFirst().map(room -> new HomeResponse.ChatRoom(room.roomId(), room.raceId(),
                room.raceName(), room.status(), room.onlineCount(), room.opensAt(), room.closesAt())).orElse(null);
    }
}
