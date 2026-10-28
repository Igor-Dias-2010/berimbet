package com.berimbet.model;

import com.berimbet.engine.MatchEngine;
import lombok.Data;

/**
 * IA por posição (4-3-3), decisões no estilo de jogo real:
 * posse → progresso; sem bola → forma + pressão seletiva; GK preso na área.
 */
@Data
public class Player {
    private int id;
    private Enums.TeamSide team;
    private Enums.PlayerRole role;
    private Enums.PlayerState state;
    private Vector2D position, velocity, homePosition;
    private PlayerAttributes attributes;
    private int cooldownTicks = 0;

    private static final double SPEED_SCALE = 0.58;
    private static final double ACCEL_SCALE = 0.55;

    /** Área penal (aprox. 16.5 m em campo 100u) */
    private static final double BOX_DEPTH = 16.5;
    private static final double BOX_Y_MIN = 21;
    private static final double BOX_Y_MAX = 79;

    public Player(int id, Enums.TeamSide team, Enums.PlayerRole role, Vector2D startPos) {
        this.id = id;
        this.team = team;
        this.role = role;
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
            this.position.add(this.velocity);
            keepInBounds();
            return;
        }
        decideState(engine, closestHome, closestAway);
        executeRole(engine);
        this.position.add(this.velocity);
        applyFriction();
        keepInBounds();
    }

    private void decideState(MatchEngine engine, Player closestHome, Player closestAway) {
        Ball ball = engine.getBall();
        if (role == Enums.PlayerRole.GOALKEEPER) {
            this.state = Enums.PlayerState.IDLE;
            return;
        }
        if (ball.getOwner() == this) {
            this.state = Enums.PlayerState.WITH_BALL;
            return;
        }
        if (ball.getOwner() != null && ball.getOwner().getTeam() == this.team) {
            this.state = Enums.PlayerState.SUPPORTING;
            return;
        }
        if (ball.getOwner() != null) {
            this.state = Enums.PlayerState.DEFENDING;
            return;
        }
        Player closest = team == Enums.TeamSide.HOME ? closestHome : closestAway;
        this.state = (closest == this) ? Enums.PlayerState.SUPPORTING : Enums.PlayerState.IDLE;
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

    // ───────── GOLEIRO: nunca sai da área ─────────
    private void behaveGoalkeeper(MatchEngine engine) {
        Ball ball = engine.getBall();
        double goalX = team == Enums.TeamSide.HOME ? 5.5 : 94.5;
        double boxEdge = team == Enums.TeamSide.HOME ? BOX_DEPTH : 100 - BOX_DEPTH;

        if (ball.getOwner() == this) {
            // Saída rápida: lateral ou longo
            double ty = 25 + Math.random() * 50;
            double tx = team == Enums.TeamSide.HOME ? (Math.random() < 0.55 ? 42 : 55) : (Math.random() < 0.55 ? 58 : 45);
            Vector2D dir = Vector2D.sub(new Vector2D(tx, ty), position);
            dir.normalize();
            dir.mult(2.0 + attributes.getPassing() * 0.5);
            ball.kick(dir);
            cooldownTicks = 10;
            return;
        }

        double targetY = clamp(ball.getPosition().y, 38, 62);
        double targetX = goalX;

        // Só avança dentro da área se bola estiver nela e perigosa
        boolean ballInBox = isInOwnBox(ball.getPosition());
        double distBall = position.distance(ball.getPosition());
        if (ballInBox && ball.getOwner() == null && distBall < 14) {
            targetX = clamp(ball.getPosition().x,
                team == Enums.TeamSide.HOME ? 2 : boxEdge,
                team == Enums.TeamSide.HOME ? boxEdge : 98);
            targetY = clamp(ball.getPosition().y, BOX_Y_MIN + 2, BOX_Y_MAX - 2);
            steerTowards(new Vector2D(targetX, targetY), attributes.getSpeed() * SPEED_SCALE * 1.15);
            if (distBall < 2.4) ball.setOwner(this);
            return;
        }

        // Posicionamento: fecha ângulo (entre bola e centro do gol)
        double blend = 0.35;
        targetY = clamp(ball.getPosition().y * blend + 50 * (1 - blend), 40, 60);
        steerTowards(new Vector2D(goalX, targetY), attributes.getSpeed() * SPEED_SCALE);
    }

    // ───────── ZAGA: linha + marcação ─────────
    private void behaveDefender(MatchEngine engine) {
        Ball ball = engine.getBall();
        double spd = attributes.getSpeed() * SPEED_SCALE * 0.95;
        double maxX = team == Enums.TeamSide.HOME ? 54 : 46;

        if (state == Enums.PlayerState.WITH_BALL) {
            playWithBall(engine, false);
            return;
        }

        if (state == Enums.PlayerState.DEFENDING) {
            Player threat = nearestThreat(engine);
            Vector2D mark;
            if (threat != null) {
                double goalX = team == Enums.TeamSide.HOME ? 0 : 100;
                mark = new Vector2D(
                    threat.getPosition().x * 0.6 + goalX * 0.4,
                    threat.getPosition().y * 0.65 + homePosition.y * 0.35
                );
            } else {
                mark = defensiveLine(ball);
            }
            if (ball.getOwner() != null && position.distance(ball.getOwner().getPosition()) < 2.8
                    && Math.random() < attributes.getTackling() * 0.09) {
                ball.getOwner().setCooldownTicks(16);
                ball.setOwner(this);
                return;
            }
            if (team == Enums.TeamSide.HOME && mark.x > maxX) mark.x = maxX;
            if (team == Enums.TeamSide.AWAY && mark.x < maxX) mark.x = maxX;
            steerTowards(mark, spd);
            return;
        }

        if (state == Enums.PlayerState.SUPPORTING && ball.getOwner() == null) {
            boolean ownHalf = team == Enums.TeamSide.HOME ? ball.getPosition().x < 52 : ball.getPosition().x > 48;
            if (ownHalf) {
                steerTowards(ball.getPosition(), spd * 1.1);
                return;
            }
        }

        Vector2D shape = defensiveLine(ball);
        if (team == Enums.TeamSide.HOME && shape.x > maxX) shape.x = maxX;
        if (team == Enums.TeamSide.AWAY && shape.x < maxX) shape.x = maxX;
        steerTowards(shape, spd * 0.8);
    }

    // ───────── MEIO: ligação + pressão ─────────
    private void behaveMidfielder(MatchEngine engine) {
        Ball ball = engine.getBall();
        double spd = attributes.getSpeed() * SPEED_SCALE;

        if (state == Enums.PlayerState.WITH_BALL) {
            playWithBall(engine, true);
            return;
        }

        if (state == Enums.PlayerState.DEFENDING) {
            if (ball.getOwner() != null) {
                double d = position.distance(ball.getOwner().getPosition());
                if (d < 20) {
                    if (d < 2.7 && Math.random() < attributes.getTackling() * 0.08) {
                        ball.getOwner().setCooldownTicks(14);
                        ball.setOwner(this);
                        return;
                    }
                    steerTowards(ball.getOwner().getPosition(), spd);
                    return;
                }
            }
            Vector2D cover = new Vector2D(
                homePosition.x * 0.4 + ball.getPosition().x * 0.6,
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
            double ahead = team == Enums.TeamSide.HOME ? 12 : -12;
            double side = homePosition.y < 50 ? -11 : 11;
            Vector2D support = new Vector2D(
                clamp(owner.getPosition().x + ahead, 14, 86),
                clamp(owner.getPosition().y + side * 0.45, 10, 90)
            );
            support.x = support.x * 0.6 + homePosition.x * 0.4;
            support.y = support.y * 0.55 + homePosition.y * 0.45;
            steerTowards(support, spd * 0.9);
            return;
        }

        holdShape(0.6);
    }

    // ───────── ATAQUE: profundidade + finalização ─────────
    private void behaveForward(MatchEngine engine) {
        Ball ball = engine.getBall();
        double spd = attributes.getSpeed() * SPEED_SCALE * 1.08;
        double attackDir = team == Enums.TeamSide.HOME ? 1 : -1;
        double goalX = team == Enums.TeamSide.HOME ? 100 : 0;

        if (state == Enums.PlayerState.WITH_BALL) {
            playWithBall(engine, true);
            return;
        }

        if (state == Enums.PlayerState.DEFENDING) {
            boolean high = team == Enums.TeamSide.HOME ? ball.getPosition().x > 52 : ball.getPosition().x < 48;
            if (high && ball.getOwner() != null) {
                steerTowards(ball.getOwner().getPosition(), spd * 0.95);
            } else {
                steerTowards(new Vector2D(homePosition.x - attackDir * 6,
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
            double runX = owner.getPosition().x + attackDir * 16;
            double runY = homePosition.y;
            if (Math.abs(homePosition.y - 50) > 14) {
                runY += (homePosition.y < 50 ? -6 : 6);
            } else {
                runY += (Math.random() < 0.5 ? -4 : 4);
            }
            steerTowards(new Vector2D(clamp(runX, 10, 90), clamp(runY, 8, 92)), spd);
            return;
        }

        steerTowards(new Vector2D(
            homePosition.x + attackDir * 4,
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

    private Vector2D defensiveLine(Ball ball) {
        double baseX = homePosition.x;
        double pull = (ball.getPosition().x - 50) * 0.14;
        if (team == Enums.TeamSide.HOME) baseX = clamp(homePosition.x + pull, 14, 50);
        else baseX = clamp(homePosition.x + pull, 50, 86);
        double y = homePosition.y * 0.6 + ball.getPosition().y * 0.4;
        return new Vector2D(baseX, clamp(y, 10, 90));
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

    private boolean isInOwnBox(Vector2D pos) {
        if (pos.y < BOX_Y_MIN || pos.y > BOX_Y_MAX) return false;
        if (team == Enums.TeamSide.HOME) return pos.x <= BOX_DEPTH;
        return pos.x >= 100 - BOX_DEPTH;
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

    private void keepInBounds() {
        if (role == Enums.PlayerRole.GOALKEEPER) {
            // GK preso na própria área penal
            if (team == Enums.TeamSide.HOME) {
                position.x = clamp(position.x, 1.5, BOX_DEPTH);
            } else {
                position.x = clamp(position.x, 100 - BOX_DEPTH, 98.5);
            }
            position.y = clamp(position.y, BOX_Y_MIN, BOX_Y_MAX);
            return;
        }
        position.x = clamp(position.x, 2.5, 97.5);
        position.y = clamp(position.y, 2.5, 97.5);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}