package com.berimbet.dto;

import com.berimbet.engine.MatchEngine; 
import com.berimbet.model.Player; 
import lombok.Data;
import java.util.List; 
import java.util.stream.Collectors;

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
        this.ball = new BallDTO(engine.getBall().getPosition().x, engine.getBall().getPosition().y);
        this.players = engine.getPlayers().stream().map(PlayerDTO::new).collect(Collectors.toList());
    }

    @Data 
    public static class BallDTO { 
        private double x, y; 
        public BallDTO(double x, double y) { this.x = x; this.y = y; } 
    }
    
    @Data 
    public static class PlayerDTO {
        private int id, team; private double x, y;
        public PlayerDTO(Player p) { 
            this.id = p.getId(); this.x = p.getPosition().x; this.y = p.getPosition().y; this.team = p.getTeam().ordinal(); 
        }
    }
}