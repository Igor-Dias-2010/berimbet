package com.berimbet.dto;

import com.berimbet.engine.MatchEngine;
import com.berimbet.model.Player;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class MatchStateDTO {
    private String type = "STATE";
    private int minute;
    private int[] score;
    private BallDTO ball;
    private List<PlayerDTO> players;

    public MatchStateDTO(MatchEngine engine) {
        this.minute = engine.getMatchMinute();
        this.score = new int[]{engine.getHomeScore(), engine.getAwayScore()};

        double bx = engine.getBall().getPosition().x;
        double by = engine.getBall().getPosition().y;
        // 1 casa decimal → JSON menor e estável
        this.ball = new BallDTO(round1(bx), round1(by));

        List<Player> src = engine.getPlayers();
        this.players = new ArrayList<>(src.size());
        for (Player p : src) {
            this.players.add(new PlayerDTO(p));
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    @Data
    public static class BallDTO {
        private double x, y;
        public BallDTO(double x, double y) { this.x = x; this.y = y; }
    }

    @Data
    public static class PlayerDTO {
        private int id, team;
        private double x, y;
        public PlayerDTO(Player p) {
            this.id = p.getId();
            this.team = p.getTeam().ordinal();
            this.x = Math.round(p.getPosition().x * 10.0) / 10.0;
            this.y = Math.round(p.getPosition().y * 10.0) / 10.0;
        }
    }
}