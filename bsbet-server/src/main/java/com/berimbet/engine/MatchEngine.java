package com.berimbet.engine;

import com.berimbet.model.*;
import lombok.Getter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * Motor com formações reais embutidas (sem classe extra):
 * 4-3-3, 4-4-2, 4-2-3-1, 3-5-2, 3-4-3 — uma sorteada por partida.
 */
public class MatchEngine implements Runnable {
    @Getter private final String matchId;
    @Getter private final String formationLabel;
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
    private static final int SEND_EVERY = 2;
    private static final double REAL_SECONDS_PER_MATCH_MINUTE = 0.55;
    private static final Random RNG = new Random();

    // ── Formações: label, posições HOME [11][2], roles[11], shirts[11] ──
    private static final Object[][] FORMATIONS = {
        {
            "4-3-3",
            new double[][]{
                {3.5, 50},
                {20, 82}, {16, 61}, {16, 39}, {20, 18},
                {40, 68}, {36, 50}, {40, 32},
                {62, 80}, {68, 50}, {62, 20}
            },
            new Enums.PlayerRole[]{
                Enums.PlayerRole.GOALKEEPER,
                Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER,
                Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER,
                Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD
            },
            new int[]{1, 2, 5, 6, 3, 8, 4, 10, 7, 9, 11}
        },
        {
            "4-4-2",
            new double[][]{
                {3.5, 50},
                {20, 82}, {16, 60}, {16, 40}, {20, 18},
                {42, 82}, {40, 60}, {40, 40}, {42, 18},
                {66, 60}, {66, 40}
            },
            new Enums.PlayerRole[]{
                Enums.PlayerRole.GOALKEEPER,
                Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER,
                Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER,
                Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD
            },
            new int[]{1, 2, 5, 6, 3, 7, 4, 8, 11, 9, 10}
        },
        {
            "4-2-3-1",
            new double[][]{
                {3.5, 50},
                {20, 82}, {16, 60}, {16, 40}, {20, 18},
                {34, 62}, {34, 38},
                {52, 80}, {52, 50}, {52, 20},
                {70, 50}
            },
            new Enums.PlayerRole[]{
                Enums.PlayerRole.GOALKEEPER,
                Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER,
                Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER,
                Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER,
                Enums.PlayerRole.FORWARD
            },
            new int[]{1, 2, 5, 6, 3, 4, 8, 7, 10, 11, 9}
        },
        {
            "3-5-2",
            new double[][]{
                {3.5, 50},
                {16, 70}, {14, 50}, {16, 30},
                {36, 88}, {38, 65}, {36, 50}, {38, 35}, {36, 12},
                {66, 58}, {66, 42}
            },
            new Enums.PlayerRole[]{
                Enums.PlayerRole.GOALKEEPER,
                Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER,
                Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER,
                Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER,
                Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD
            },
            new int[]{1, 4, 5, 6, 2, 8, 10, 7, 3, 9, 11}
        },
        {
            "3-4-3",
            new double[][]{
                {3.5, 50},
                {16, 70}, {14, 50}, {16, 30},
                {40, 82}, {38, 60}, {38, 40}, {40, 18},
                {64, 80}, {68, 50}, {64, 20}
            },
            new Enums.PlayerRole[]{
                Enums.PlayerRole.GOALKEEPER,
                Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER, Enums.PlayerRole.DEFENDER,
                Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER, Enums.PlayerRole.MIDFIELDER,
                Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD, Enums.PlayerRole.FORWARD
            },
            new int[]{1, 4, 5, 6, 2, 8, 10, 3, 7, 9, 11}
        }
    };

    public MatchEngine(String matchId, Runnable onTickCallback, Runnable onMatchEndCallback,
                       BiConsumer<String, MatchEngine> onEventCallback) {
        this.matchId = matchId;
        this.onTickCallback = onTickCallback;
        this.onMatchEndCallback = onMatchEndCallback;
        this.onEventCallback = onEventCallback;
        Object[] f = FORMATIONS[RNG.nextInt(FORMATIONS.length)];
        this.formationLabel = (String) f[0];
        setupTeams((double[][]) f[1], (Enums.PlayerRole[]) f[2], (int[]) f[3]);
    }

    private void setupTeams(double[][] home, Enums.PlayerRole[] roles, int[] shirts) {
        for (int i = 0; i < 11; i++) {
            double ax = 100.0 - home[i][0];
            double ay = 100.0 - home[i][1];
            players.add(new Player(i, Enums.TeamSide.HOME, roles[i], i, shirts[i],
                new Vector2D(home[i][0], home[i][1])));
            players.add(new Player(i + 11, Enums.TeamSide.AWAY, roles[i], i, shirts[i],
                new Vector2D(ax, ay)));
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
        if (onEventCallback != null) {
            onEventCallback.accept("Formação: " + formationLabel, this);
        }
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
        if (ball.getOwner() != null) return;
        if (p.getCooldownTicks() > 0) return;
        if (p.getPosition().distance(ball.getPosition()) >= 2.5) return;

        if (p.getRole() == Enums.PlayerRole.GOALKEEPER) {
            if (p.isInOwnPenaltyArea(ball.getPosition())) {
                ball.setOwner(p);
            }
            return;
        }
        if (isInGoalMouth(p.getPosition(), p.getTeam())) return;
        ball.setOwner(p);
    }

    public boolean isInGoalMouth(Vector2D pos, Enums.TeamSide team) {
        if (pos.y < 36 || pos.y > 64) return false;
        if (team == Enums.TeamSide.HOME) return pos.x < 9;
        return pos.x > 91;
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
            if (progress < 2 && dist > 15) score -= 8;
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }
}