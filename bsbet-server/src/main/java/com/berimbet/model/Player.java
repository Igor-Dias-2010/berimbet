package com.berimbet.model;

import com.berimbet.engine.MatchEngine;
import lombok.Data;

/**
 * Comportamento por posição no 4-3-3 (IFAB + prática de jogo real).
 *
 * slot: 0=GK 1=RB 2=RCB 3=LCB 4=LB 5=RCM 6=CDM 7=LCM 8=RW 9=ST 10=LW
 *
 * GK: fica NA LINHA DO GOL; só sai dentro da área do gol (6 jardas / 5,5 m)
 *     para bola solta muito perto — nunca vaga pela área penal inteira.
 * DEF: linha defensiva; proibido estacionar na boca do gol.
 */
@Data
public class Player {
    private int id;
    private int shirtNumber;
    /** Índice tático 0–10 dentro do 4-3-3 */
    private int slot;
    private Enums.TeamSide team;
    private Enums.PlayerRole role;
    private Enums.PlayerState state;
    private Vector2D position, velocity, homePosition;
    private PlayerAttributes attributes;
    private int cooldownTicks = 0;

    private static final double SPEED_SCALE = 0.58;
    private static final double ACCEL_SCALE = 0.55;

    /** IFAB: área penal 16,5 m; área do gol 5,5 m (campo normalizado 0–100) */
    private static final double PENALTY_DEPTH = 16.5;
    private static final double GOAL_AREA_DEPTH = 5.5;
    private static final double PENALTY_Y_MIN = 20;
    private static final double PENALTY_Y_MAX = 80;
    private static final double GOAL_MOUTH_Y_MIN = 39;
    private static final double GOAL_MOUTH_Y_MAX = 61;

    public Player(int id, Enums.TeamSide team, Enums.PlayerRole role, int slot, int shirtNumber, Vector2D startPos) {
        this.id = id;
        this.team = team;
        this.role = role;
        this.slot = slot;
        this.shirtNumber = shirtNumber;
        this.homePosition = new Vector2D(startPos.x, startPos.y);
        this.position = new Vector2D(startPos.x, startPos.y);
        this.velocity = new Vector2D(0, 0);
        this.state = Enums.PlayerState.IDLE;
        this.attributes = PlayerAttributes.generateForRole(role);
    }

    public void update(MatchEngine engine, Player closestHome, Player closestAway) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            applyFriction();
            position.add(velocity);
            keepInBounds(engine);
            return;
        }
        decideState(engine, closestHome, closestAway);
        executeRole(engine);
        position.add(velocity);
        applyFriction();
        keepInBounds(engine);
    }

    private void decideState(MatchEngine engine, Player closestHome, Player closestAway) {
        Ball ball = engine.getBall();
        if (role == Enums.PlayerRole.GOALKEEPER) {
            state = Enums.PlayerState.IDLE;
            return;
        }
        if (ball.getOwner() == this) {
            state = Enums.PlayerState.WITH_BALL;
            return;
        }
        if (ball.getOwner() != null && ball.getOwner().getTeam() == team) {
            state = Enums.PlayerState.SUPPORTING;
            return;
        }
        if (ball.getOwner() != null) {
            state = Enums.PlayerState.DEFENDING;
            return;
        }
        Player closest = team == Enums.TeamSide.HOME ? closestHome : closestAway;
        state = (closest == this) ? Enums.PlayerState.SUPPORTING : Enums.PlayerState.IDLE;
    }

    private void executeRole(MatchEngine engine) {
        switch (role) {
            case GOALKEEPER -> behaveGoalkeeper(engine);
            case DEFENDER -> behaveDefender(engine);
            case MIDFIELDER -> behaveMidfielder(engine);
            case FORWARD -> behaveForward(engine);
            default -> holdShape(0.5);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // GOLEIRO — posição base = LINHA DO GOL (como em cobrança de pênalti IFAB)
    // Só avança dentro da ÁREA DO GOL (5,5 m) se a bola estiver solta e perto.
    // ═══════════════════════════════════════════════════════════
    private void behaveGoalkeeper(MatchEngine engine) {
        Ball ball = engine.getBall();
        double goalLineX = team == Enums.TeamSide.HOME ? 3.0 : 97.0;

        if (ball.getOwner() == this) {
            // Reposição: lateral ou longo (construção)
            double ty = 28 + Math.random() * 44;
            double tx = team == Enums.TeamSide.HOME
                ? (Math.random() < 0.5 ? 38 : 52)
                : (Math.random() < 0.5 ? 62 : 48);
            Vector2D dir = Vector2D.sub(new Vector2D(tx, ty), position);
            dir.normalize();
            dir.mult(2.1 + attributes.getPassing() * 0.45);
            ball.kick(dir);
            cooldownTicks = 10;
            return;
        }

        double ballY = clamp(ball.getPosition().y, GOAL_MOUTH_Y_MIN, GOAL_MOUTH_Y_MAX);
        // Fecha o ângulo: mistura bola com centro do gol
        double targetY = clamp(ballY * 0.55 + 50 * 0.45, 42, 58);

        boolean ballInGoalArea = isInOwnGoalArea(ball.getPosition());
        double distBall = position.distance(ball.getPosition());

        // Sai da linha SÓ se bola está na área do gol, solta, e a < ~8 u
        if (ballInGoalArea && ball.getOwner() == null && distBall < 8) {
            double maxX = team == Enums.TeamSide.HOME ? GOAL_AREA_DEPTH : 100 - GOAL_AREA_DEPTH;
            double minX = team == Enums.TeamSide.HOME ? 2.0 : 100 - GOAL_AREA_DEPTH;
            double tx = clamp(ball.getPosition().x, Math.min(minX, maxX), Math.max(minX, maxX));
            double ty = clamp(ball.getPosition().y, 36, 64);
            steerTowards(new Vector2D(tx, ty), attributes.getSpeed() * SPEED_SCALE * 1.2);
            if (distBall < 2.3) ball.setOwner(this);
            return;
        }

        // Posição padrão: NA LINHA DO GOL, acompanhando o Y
        steerTowards(new Vector2D(goalLineX, targetY), attributes.getSpeed() * SPEED_SCALE * 0.95);
    }

    // ═══════════════════════════════════════════════════════════
    // ZAGA — 2=RB, 5=RCB, 6=LCB, 3=LB
    // Nunca estaciona na boca do gol (isso é função do goleiro).
    // ═══════════════════════════════════════════════════════════
    private void behaveDefender(MatchEngine engine) {
        Ball ball = engine.getBall();
        double spd = attributes.getSpeed() * SPEED_SCALE * 0.95;
        boolean fullback = (slot == 1 || slot == 4); // RB / LB

        if (state == Enums.PlayerState.WITH_BALL) {
            playWithBall(engine, false);
            return;
        }

        if (state == Enums.PlayerState.DEFENDING) {
            Player threat = nearestThreat(engine);
            Vector2D mark;
            if (threat != null) {
                double goalX = team == Enums.TeamSide.HOME ? 0 : 100;
                // Entre o atacante e o gol, sem entrar na boca
                mark = new Vector2D(
                    threat.getPosition().x * 0.55 + goalX * 0.2 + homePosition.x * 0.25,
                    threat.getPosition().y * 0.6 + homePosition.y * 0.4
                );
            } else {
                mark = defensiveLinePos(ball, fullback);
            }
            if (ball.getOwner() != null && position.distance(ball.getOwner().getPosition()) < 2.8
                    && Math.random() < attributes.getTackling() * 0.09) {
                ball.getOwner().setCooldownTicks(16);
                ball.setOwner(this);
                return;
            }
            mark = clampAwayFromGoalMouth(mark);
            steerTowards(mark, spd);
            return;
        }

        if (state == Enums.PlayerState.SUPPORTING && ball.getOwner() == null) {
            boolean ownHalf = team == Enums.TeamSide.HOME
                ? ball.getPosition().x < 52 : ball.getPosition().x > 48;
            if (ownHalf && !engine.isInGoalMouth(ball.getPosition(), team)) {
                steerTowards(ball.getPosition(), spd * 1.05);
                return;
            }
        }

        Vector2D shape = clampAwayFromGoalMouth(defensiveLinePos(ball, fullback));
        steerTowards(shape, spd * 0.8);
    }

    private void behaveMidfielder(MatchEngine engine) {
        Ball ball = engine.getBall();
        double spd = attributes.getSpeed() * SPEED_SCALE;
        boolean cdm = (slot == 6);

        if (state == Enums.PlayerState.WITH_BALL) {
            playWithBall(engine, !cdm);
            return;
        }
        if (state == Enums.PlayerState.DEFENDING) {
            if (ball.getOwner() != null) {
                double d = position.distance(ball.getOwner().getPosition());
                if (d < (cdm ? 22 : 18)) {
                    if (d < 2.7 && Math.random() < attributes.getTackling() * 0.08) {
                        ball.getOwner().setCooldownTicks(14);
                        ball.setOwner(this);
                        return;
                    }
                    steerTowards(ball.getOwner().getPosition(), spd * (cdm ? 0.95 : 1.0));
                    return;
                }
            }
            Vector2D cover = new Vector2D(
                homePosition.x * 0.45 + ball.getPosition().x * 0.55,
                homePosition.y * 0.55 + ball.getPosition().y * 0.45
            );
            steerTowards(cover, spd * 0.85);
            return;
        }
        if (state == Enums.PlayerState.SUPPORTING) {
            if (ball.getOwner() == null) {
                steerTowards(ball.getPosition(), spd * 1.05);
                return;
            }
            Player owner = ball.getOwner();
            double ahead = team == Enums.TeamSide.HOME ? 11 : -11;
            if (cdm) ahead *= 0.4;
            double side = homePosition.y < 50 ? -10 : 10;
            Vector2D support = new Vector2D(
                clamp(owner.getPosition().x + ahead, 14, 86),
                clamp(owner.getPosition().y + side * 0.4, 10, 90)
            );
            support.x = support.x * 0.55 + homePosition.x * 0.45;
            support.y = support.y * 0.5 + homePosition.y * 0.5;
            steerTowards(support, spd * 0.9);
            return;
        }
        holdShape(0.6);
    }

    private void behaveForward(MatchEngine engine) {
        Ball ball = engine.getBall();
        double spd = attributes.getSpeed() * SPEED_SCALE * 1.08;
        double attackDir = team == Enums.TeamSide.HOME ? 1 : -1;
        boolean st = (slot == 9);
        boolean wing = (slot == 8 || slot == 10);

        if (state == Enums.PlayerState.WITH_BALL) {
            playWithBall(engine, true);
            return;
        }
        if (state == Enums.PlayerState.DEFENDING) {
            boolean high = team == Enums.TeamSide.HOME
                ? ball.getPosition().x > 52 : ball.getPosition().x < 48;
            if (high && ball.getOwner() != null) {
                steerTowards(ball.getOwner().getPosition(), spd * 0.95);
            } else {
                steerTowards(new Vector2D(homePosition.x - attackDir * 5,
                    homePosition.y * 0.7 + ball.getPosition().y * 0.3), spd * 0.75);
            }
            return;
        }
        if (state == Enums.PlayerState.SUPPORTING) {
            if (ball.getOwner() == null) {
                steerTowards(ball.getPosition(), spd * 1.1);
                return;
            }
            Player owner = ball.getOwner();
            double runX = owner.getPosition().x + attackDir * (st ? 14 : 16);
            double runY = homePosition.y;
            if (wing) runY += (homePosition.y < 50 ? -7 : 7);
            else runY += (Math.random() < 0.5 ? -3 : 3);
            steerTowards(new Vector2D(clamp(runX, 10, 90), clamp(runY, 8, 92)), spd);
            return;
        }
        steerTowards(new Vector2D(
            homePosition.x + attackDir * 3,
            homePosition.y * 0.75 + ball.getPosition().y * 0.25
        ), spd * 0.6);
    }

    private void playWithBall(MatchEngine engine, boolean attackMinded) {
        Ball ball = engine.getBall();
        double goalX = team == Enums.TeamSide.HOME ? 100 : 0;
        double distGoal = Math.abs(position.x - goalX);
        double spd = attributes.getSpeed() * SPEED_SCALE * 0.75;

        boolean inBox = distGoal < 17;
        boolean nearBox = distGoal < 28;
        double shootP = attributes.getShooting() * (inBox ? 0.11 : nearBox ? 0.045 : 0.01);
        if (attackMinded && distGoal < 34 && Math.random() < shootP) {
            double aimY = 50 + (Math.random() * 16 - 8);
            if (Math.random() < 0.4) aimY = Math.random() < 0.5 ? 41 : 59;
            Vector2D shot = Vector2D.sub(new Vector2D(goalX, aimY), position);
            shot.normalize();
            shot.mult(inBox ? 1.85 + attributes.getShooting() * 0.65 : 1.35 + attributes.getShooting() * 0.4);
            ball.kick(shot);
            cooldownTicks = 9;
            return;
        }
        Player best = engine.findBestPassOption(this);
        double passP = attributes.getPassing() * (role == Enums.PlayerRole.DEFENDER ? 0.055 : 0.038);
        if (best != null && Math.random() < passP) {
            Vector2D pass = Vector2D.sub(best.getPosition(), position);
            pass.normalize();
            pass.mult(1.25 + attributes.getPassing() * 0.55);
            ball.kick(pass);
            cooldownTicks = 6;
            return;
        }
        double channelY = clamp(position.y + (50 - position.y) * 0.1, 12, 88);
        steerTowards(new Vector2D(goalX, channelY), spd);
    }

    private Vector2D defensiveLinePos(Ball ball, boolean fullback) {
        double baseX = homePosition.x;
        double pull = (ball.getPosition().x - 50) * 0.12;
        if (team == Enums.TeamSide.HOME) baseX = clamp(homePosition.x + pull, 14, 50);
        else baseX = clamp(homePosition.x + pull, 50, 86);
        // laterais ficam mais abertos no Y
        double yMix = fullback ? 0.25 : 0.4;
        double y = homePosition.y * (1 - yMix) + ball.getPosition().y * yMix;
        return new Vector2D(baseX, clamp(y, fullback ? 8 : 14, fullback ? 92 : 86));
    }

    /** Impede zagueiro de ocupar o espaço do goleiro */
    private Vector2D clampAwayFromGoalMouth(Vector2D p) {
        if (team == Enums.TeamSide.HOME && p.x < 11) p.x = 11;
        if (team == Enums.TeamSide.AWAY && p.x > 89) p.x = 89;
        return p;
    }

    private Player nearestThreat(MatchEngine engine) {
        Player best = null;
        double min = Double.MAX_VALUE;
        for (Player p : engine.getPlayers()) {
            if (p.getTeam() == team) continue;
            if (p.getRole() == Enums.PlayerRole.GOALKEEPER) continue;
            double d = position.distance(p.getPosition());
            if (d < min) { min = d; best = p; }
        }
        return best;
    }

    public boolean isInOwnPenaltyArea(Vector2D pos) {
        if (pos.y < PENALTY_Y_MIN || pos.y > PENALTY_Y_MAX) return false;
        if (team == Enums.TeamSide.HOME) return pos.x <= PENALTY_DEPTH;
        return pos.x >= 100 - PENALTY_DEPTH;
    }

    private boolean isInOwnGoalArea(Vector2D pos) {
        if (pos.y < 34 || pos.y > 66) return false;
        if (team == Enums.TeamSide.HOME) return pos.x <= GOAL_AREA_DEPTH;
        return pos.x >= 100 - GOAL_AREA_DEPTH;
    }

    private void holdShape(double f) {
        steerTowards(homePosition, attributes.getSpeed() * SPEED_SCALE * f);
    }

    private void steerTowards(Vector2D target, double maxSpeed) {
        Vector2D desired = Vector2D.sub(target, position);
        double d = desired.mag();
        if (d > 0.3) {
            desired.normalize();
            desired.mult(d < 4.5 ? maxSpeed * (d / 4.5) : maxSpeed);
            Vector2D steer = Vector2D.sub(desired, velocity);
            steer.limit(attributes.getAcceleration() * ACCEL_SCALE);
            velocity.add(steer);
        }
    }

    private void applyFriction() {
        velocity.mult(0.86);
    }

    private void keepInBounds(MatchEngine engine) {
        if (role == Enums.PlayerRole.GOALKEEPER) {
            // GK: preso na área do gol + um pouco da penal só no Y da boca
            if (team == Enums.TeamSide.HOME) {
                position.x = clamp(position.x, 1.5, GOAL_AREA_DEPTH + 0.5);
            } else {
                position.x = clamp(position.x, 100 - GOAL_AREA_DEPTH - 0.5, 98.5);
            }
            position.y = clamp(position.y, 36, 64);
            velocity.x *= 0.5;
            return;
        }
        // Defensores: nunca dentro da boca do gol
        if (role == Enums.PlayerRole.DEFENDER) {
            position = clampAwayFromGoalMouth(position);
        }
        position.x = clamp(position.x, 2.5, 97.5);
        position.y = clamp(position.y, 2.5, 97.5);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}