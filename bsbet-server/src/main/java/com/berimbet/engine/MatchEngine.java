package com.berimbet.engine;

import com.berimbet.model.*;
import lombok.Getter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Motor 4-3-3 — ritmo de partida real (rápido de assistir, decisões claras).
 * Física 24 Hz · rede ~12 Hz · cliente interpola.
 */
public class MatchEngine implements Runnable {
    @Getter private final String matchId;
    @Getter private final List<Player> players = new ArrayList<>();
    @Getter private final Ball ball = new Ball();
    @Getter private int homeScore = 0, awayScore = 0, matchMinute = 0;

    private int ticks = 0;
    private int sendCounter = 0;
    private boolean isRunning = false, isGoalCelebration = false;
    private final Runnable onTickCallback, onMatchEndCallback;
    private final BiConsumer<String, MatchEngine> onEventCallback;

    private static final double TICKS_PER_SEC = 24.0;
    private static final double NS_PER_TICK = 1_000_000_000.0 / TICKS_PER_SEC;
    private static final int SEND_EVERY = 2; // ~12 updates/s
    /** ~0.55 s real por minuto de jogo → 90' ≈ 50 s (ou termina em 2 gols) */
    private static final double REAL_SECONDS_PER_MATCH_MINUTE = 0.55;

    public MatchEngine(String matchId, Runnable onTickCallback, Runnable onMatchEndCallback,
                       BiConsumer<String, MatchEngine> onEventCallback) {
        this.matchId = matchId;
        this.onTickCallback = onTickCallback;
        this.onMatchEndCallback = onMatchEndCallback;
        this.onEventCallback = onEventCallback;
        setupTeams();
    }

    private void setupTeams() {
        // 4-3-3 (IFAB / uso comum em clubes modernos)
        double[][] homeForm = {
            {6, 50},
            {20, 16}, {20, 36}, {20, 64}, {20, 84},
            {40, 26}, {40, 50}, {40, 74},
            {62, 20}, {66, 50}, {62, 80}
        };
        double[][] awayForm = {
            {94, 50},
            {80, 16}, {80, 36}, {80, 64}, {80, 84},
            {60, 26}, {60, 50}, {60, 74},
            {38, 20}, {34, 50}, {38, 80}
        };
        Enums.PlayerRole[] roles = {
            Enums.PlayerRole.GOALKEEPER,
            Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER,
            Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER,
            Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER,
            Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD
        };
        for (int i = 0; i < 11; i++) {
            players.add(new Player(i, Enums.TeamSide.HOME, roles[i], new Vector2D(homeForm[i][0], homeForm[i][1])));
            players.add(new Player(i + 11, Enums.TeamSide.AWAY, roles[i], new Vector2D(awayForm[i][0], awayForm[i][1])));
        }
    }

    public void start() {
        this.isRunning = true;
        new Thread(this, "match-" + matchId).start();
    }

    public void stop() {
        this.isRunning = false;
    }

    @Override
    public void run() {
        long lastTime = System.nanoTime();
        double delta = 0;
        while (isRunning) {
            long now = System.nanoTime();
            delta += (now - lastTime) / NS_PER_TICK;
            lastTime = now;

            int steps = 0;
            while (delta >= 1 && isRunning && steps < 3) {
                tick();
                delta--;
                steps++;
                sendCounter++;
                if (sendCounter >= SEND_EVERY && onTickCallback != null) {
                    onTickCallback.run();
                    sendCounter = 0;
                }
            }
            if (delta > 3) delta = 0;

            try {
                Thread.sleep(28);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void tick() {
        if (isGoalCelebration) return;

        ticks++;
        matchMinute = Math.min(90, (int) (ticks / TICKS_PER_SEC / REAL_SECONDS_PER_MATCH_MINUTE));

        Player closestHome = getClosestPlayerToBall(Enums.TeamSide.HOME);
        Player closestAway = getClosestPlayerToBall(Enums.TeamSide.AWAY);

        for (Player p : players) {
            p.update(this, closestHome, closestAway);
            checkBallCollision(p);
        }
        ball.updatePhysics();
        checkGoals();

        if (matchMinute >= 90 || (homeScore + awayScore >= 2)) {
            isRunning = false;
            if (onMatchEndCallback != null) onMatchEndCallback.run();
        }
    }

    private void checkBallCollision(Player p) {
        if (ball.getOwner() == null
                && p.getPosition().distance(ball.getPosition()) < 2.5
                && p.getCooldownTicks() == 0) {
            ball.setOwner(p);
        }
    }

    private void checkGoals() {
        Vector2D bp = ball.getPosition();
        if (bp.y >= 39 && bp.y <= 61) {
            if (bp.x <= 1.15) {
                awayScore++;
                handleGoal(Enums.TeamSide.AWAY);
            } else if (bp.x >= 98.85) {
                homeScore++;
                handleGoal(Enums.TeamSide.HOME);
            }
        }
    }

    private void handleGoal(Enums.TeamSide scoringTeam) {
        isGoalCelebration = true;
        ball.setOwner(null);
        if (onEventCallback != null) {
            onEventCallback.accept(
                "GOL DOS " + (scoringTeam == Enums.TeamSide.HOME ? "LOBOS" : "DRAGÕES") + "!",
                this
            );
        }
        new Thread(() -> {
            try {
                Thread.sleep(2200);
                resetPositions();
                isGoalCelebration = false;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "goal-celebration").start();
    }

    private void resetPositions() {
        ball.getPosition().x = 50;
        ball.getPosition().y = 50;
        ball.getVelocity().x = 0;
        ball.getVelocity().y = 0;
        for (Player p : players) {
            p.getPosition().x = p.getHomePosition().x;
            p.getPosition().y = p.getHomePosition().y;
            p.getVelocity().x = 0;
            p.getVelocity().y = 0;
            p.setState(Enums.PlayerState.IDLE);
            p.setCooldownTicks(0);
        }
    }

    public Player getClosestPlayerToBall(Enums.TeamSide team) {
        Player closest = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : players) {
            if (p.getTeam() != team) continue;
            if (p.getRole() == Enums.PlayerRole.GOALKEEPER) continue;
            double dist = p.getPosition().distance(ball.getPosition());
            if (dist < minDist) {
                minDist = dist;
                closest = p;
            }
        }
        return closest;
    }

    public Player findBestPassOption(Player passer) {
        Player best = null;
        double bestScore = -Double.MAX_VALUE;
        double attackDir = passer.getTeam() == Enums.TeamSide.HOME ? 1 : -1;
        double goalX = passer.getTeam() == Enums.TeamSide.HOME ? 100 : 0;

        for (Player p : players) {
            if (p.getTeam() != passer.getTeam() || p == passer) continue;
            if (p.getRole() == Enums.PlayerRole.GOALKEEPER) continue;

            double dist = p.getPosition().distance(passer.getPosition());
            if (dist < 5 || dist > 42) continue;

            double progress = (p.getPosition().x - passer.getPosition().x) * attackDir;
            double distToGoal = Math.abs(p.getPosition().x - goalX);
            double score = progress * 2.8 + (100 - distToGoal) * 0.4 - dist * 0.35;

            if (p.getRole() == Enums.PlayerRole.FORWARD) score += 10;
            if (p.getRole() == Enums.PlayerRole.FORWARD && distToGoal < 22) score += 14;
            if (p.getRole() == Enums.PlayerRole.MIDFIELDER) score += 5;
            // penaliza passe lateral puro
            if (progress < 2 && dist > 15) score -= 8;

            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }
}