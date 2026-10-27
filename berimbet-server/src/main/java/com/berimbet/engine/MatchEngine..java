package com.berimbet.engine;

import com.berimbet.model.*; 
import lombok.Getter;
import java.util.ArrayList; 
import java.util.List; 
import java.util.function.BiConsumer;

public class MatchEngine implements Runnable {
    @Getter private final String matchId; 
    @Getter private final List<Player> players = new ArrayList<>();
    @Getter private final Ball ball = new Ball(); 
    @Getter private int homeScore = 0, awayScore = 0, matchMinute = 0;
    
    private int ticks = 0; 
    private boolean isRunning = false, isGoalCelebration = false;
    private final Runnable onTickCallback, onMatchEndCallback; 
    private final BiConsumer<String, MatchEngine> onEventCallback;

    public MatchEngine(String matchId, Runnable onTickCallback, Runnable onMatchEndCallback, BiConsumer<String, MatchEngine> onEventCallback) {
        this.matchId = matchId; 
        this.onTickCallback = onTickCallback; 
        this.onMatchEndCallback = onMatchEndCallback; 
        this.onEventCallback = onEventCallback; 
        setupTeams();
    }

    private void setupTeams() {
        double[][] homeForm = {{7,50}, {20,20}, {20,40}, {20,60}, {20,80}, {34,25}, {34,50}, {34,75}, {46,25}, {46,50}, {46,75}};
        double[][] awayForm = {{93,50}, {80,20}, {80,40}, {80,60}, {80,80}, {66,25}, {66,50}, {66,75}, {54,25}, {54,50}, {54,75}};
        Enums.PlayerRole[] roles = { Enums.PlayerRole.GOALKEEPER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD };
        for (int i = 0; i < 11; i++) {
            players.add(new Player(i, Enums.TeamSide.HOME, roles[i], new Vector2D(homeForm[i][0], homeForm[i][1])));
            players.add(new Player(i + 11, Enums.TeamSide.AWAY, roles[i], new Vector2D(awayForm[i][0], awayForm[i][1])));
        }
    }

    public void start() { this.isRunning = true; new Thread(this).start(); }
    public void stop() { this.isRunning = false; }

    @Override 
    public void run() {
        long lastTime = System.nanoTime(); 
        double nsPerTick = 1000000000.0 / 60.0, delta = 0;
        while (isRunning) {
            long now = System.nanoTime(); 
            delta += (now - lastTime) / nsPerTick; 
            lastTime = now;
            while (delta >= 1) { tick(); delta--; }
            if (onTickCallback != null) onTickCallback.run();
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void tick() {
        if (isGoalCelebration) return;
        ticks++; 
        matchMinute = Math.min(90, (int) ((ticks / 60.0) / 10.0 * 90.0));
        for (Player p : players) { p.update(this); checkBallCollision(p); }
        ball.updatePhysics(); 
        checkGoals();
        if (matchMinute >= 90 || (homeScore + awayScore >= 2)) { 
            isRunning = false; 
            if (onMatchEndCallback != null) onMatchEndCallback.run(); 
        }
    }

    private void checkBallCollision(Player p) { 
        if (ball.getOwner() == null && p.getPosition().distance(ball.getPosition()) < 2.5 && p.getCooldownTicks() == 0) ball.setOwner(p); 
    }

    private void checkGoals() {
        Vector2D bp = ball.getPosition();
        if (bp.y >= 39 && bp.y <= 61) {
            if (bp.x <= 1.5) { awayScore++; handleGoal(Enums.TeamSide.AWAY); } 
            else if (bp.x >= 98.5) { homeScore++; handleGoal(Enums.TeamSide.HOME); }
        }
    }

    private void handleGoal(Enums.TeamSide scoringTeam) {
        isGoalCelebration = true; 
        ball.setOwner(null);
        if (onEventCallback != null) onEventCallback.accept("GOL DOS " + (scoringTeam == Enums.TeamSide.HOME ? "LOBOS" : "DRAGÕES") + "!", this);
        new Thread(() -> { 
            try { Thread.sleep(3000); resetPositions(); isGoalCelebration = false; } catch (InterruptedException ignored) {} 
        }).start();
    }

    private void resetPositions() {
        ball.getPosition().x = 50; ball.getPosition().y = 50; ball.getVelocity().x = 0; ball.getVelocity().y = 0;
        for (Player p : players) { 
            p.getPosition().x = p.getHomePosition().x; p.getPosition().y = p.getHomePosition().y; 
            p.getVelocity().x = 0; p.getVelocity().y = 0; p.setState(Enums.PlayerState.IDLE); 
        }
    }

    public Player getClosestPlayerToBall(Enums.TeamSide team) {
        Player closest = null; double minDist = Double.MAX_VALUE;
        for (Player p : players) {
            if (p.getTeam() == team && p.getRole() != Enums.PlayerRole.GOALKEEPER) {
                double dist = p.getPosition().distance(ball.getPosition()); 
                if (dist < minDist) { minDist = dist; closest = p; }
            }
        }
        return closest;
    }

    public Player findBestPassOption(Player passer) {
        Player best = null; double bestScore = -Double.MAX_VALUE; 
        double targetGoalX = (passer.getTeam() == Enums.TeamSide.HOME) ? 100 : 0;
        for (Player p : players) {
            if (p.getTeam() == passer.getTeam() && p != passer) {
                double distToGoal = Math.abs(p.getPosition().x - targetGoalX), distToPasser = p.getPosition().distance(passer.getPosition());
                if (distToPasser > 5 && distToPasser < 40) {
                    double score = (100 - distToGoal) - (distToPasser * 0.5); 
                    if (score > bestScore) { bestScore = score; best = p; }
                }
            }
        }
        return best;
    }
}