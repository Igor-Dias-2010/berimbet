package com.berimbet.engine;

import com.berimbet.model.*;
import lombok.Getter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Motor 4-3-3:
 * - 15 ticks/s de física
 * - Envio a cada 2 ticks (~7.5 Hz) — cliente interpola → fluido e leve
 * - Partida ~75–90 s reais (ou 2 gols)
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

    private static final double TICKS_PER_SEC = 15.0;
    private static final double NS_PER_TICK = 1_000_000_000.0 / TICKS_PER_SEC;
    private static final int SEND_EVERY = 2; // ~7.5 updates/s na rede
    private static final double REAL_SECONDS_PER_MATCH_MINUTE = 0.9;

    public MatchEngine(String matchId, Runnable onTickCallback, Runnable onMatchEndCallback,
                       BiConsumer<String, MatchEngine> onEventCallback) {
        this.matchId = matchId;
        this.onTickCallback = onTickCallback;
        this.onMatchEndCallback = onMatchEndCallback;
        this.onEventCallback = onEventCallback;
        setupTeams();
    }

    private void setupTeams() {
        // Formação 4-3-3 clássica (coordenadas 0–100)
        // HOME ataca para a direita
        double[][] homeForm = {
            {5, 50},   // GK
            {18, 18}, {18, 38}, {18, 62}, {18, 82}, // DEF
            {38, 28}, {38, 50}, {38, 72},           // MID
            {58, 22}, {62, 50}, {58, 78}            // FWD
        };
        double[][] awayForm = {
            {95, 50},
            {82, 18}, {82, 38}, {82, 62}, {82, 82},
            {62, 28}, {62, 50}, {62, 72},
            {42, 22}, {38, 50}, {42, 78}
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
            while (delta >= 1 && isRunning && steps < 2) {
                tick();
                delta--;
                steps++;
                sendCounter++;
                if (sendCounter >= SEND_EVERY && onTickCallback != null) {
                    onTickCallback.run();
                    sendCounter = 0;
                }
            }
            if (delta > 2) delta = 0;

            try {
                Thread.sleep(55); // ~15 Hz loop, CPU leve
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
                && p.getPosition().distance(ball.getPosition()) < 2.6
                && p.getCooldownTicks() == 0) {
            ball.setOwner(p);
        }
    }

    private void checkGoals() {
        Vector2D bp = ball.getPosition();
        // Gol entre y 39–61 (trave), linha de gol em x≈1.2 / 98.8
        if (bp.y >= 39 && bp.y <= 61) {
            if (bp.x <= 1.2) {
                awayScore++;
                handleGoal(Enums.TeamSide.AWAY);
            } else if (bp.x >= 98.8) {
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
                Thread.sleep(2600);
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

    /**
     * Melhor opção de passe: prioriza companheiro mais avançado e com espaço,
     * evitando passe para trás demais (exceto zagueiro sob pressão).
     */
    public Player findBestPassOption(Player passer) {
        Player best = null;
        double bestScore = -Double.MAX_VALUE;
        double attackDir = passer.getTeam() == Enums.TeamSide.HOME ? 1 : -1;
        double goalX = passer.getTeam() == Enums.TeamSide.HOME ? 100 : 0;

        for (Player p : players) {
            if (p.getTeam() != passer.getTeam() || p == passer) continue;
            double dist = p.getPosition().distance(passer.getPosition());
            if (dist < 6 || dist > 38) continue;

            double progress = (p.getPosition().x - passer.getPosition().x) * attackDir;
            double distToGoal = Math.abs(p.getPosition().x - goalX);
            // Pontuação: progresso + proximidade do gol - distância do passe
            double score = progress * 2.2 + (100 - distToGoal) * 0.35 - dist * 0.4;

            // Bônus para atacante na área
            if (p.getRole() == Enums.PlayerRole.FORWARD && distToGoal < 25) score += 12;
            // Meias bons para combinar
            if (p.getRole() == Enums.PlayerRole.MIDFIELDER) score += 4;
            // Evita devolver pro goleiro sem necessidade
            if (p.getRole() == Enums.PlayerRole.GOALKEEPER) score -= 25;

            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }
}