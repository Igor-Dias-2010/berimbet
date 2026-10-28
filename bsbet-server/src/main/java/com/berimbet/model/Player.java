package com.berimbet.model;

import com.berimbet.engine.MatchEngine;
import lombok.Data;

@Data
public class Player {
    private int id;
    private Enums.TeamSide team;
    private Enums.PlayerRole role;
    private Enums.PlayerState state;
    private Vector2D position, velocity, homePosition;
    private PlayerAttributes attributes;
    private int cooldownTicks = 0;

    /** Escala global de velocidade — deixa o jogo assistível */
    private static final double SPEED_SCALE = 0.38;
    private static final double ACCEL_SCALE = 0.45;

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
        executeState(engine);
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
        } else if (ball.getOwner() != null && ball.getOwner().getTeam() == this.team) {
            this.state = Enums.PlayerState.SUPPORTING;
        } else if (ball.getOwner() != null && ball.getOwner().getTeam() != this.team) {
            this.state = Enums.PlayerState.DEFENDING;
        } else {
            Player closest = (this.team == Enums.TeamSide.HOME) ? closestHome : closestAway;
            this.state = (closest == this) ? Enums.PlayerState.SUPPORTING : Enums.PlayerState.IDLE;
        }
    }

    private void executeState(MatchEngine engine) {
        Ball ball = engine.getBall();
        double targetGoalX = (team == Enums.TeamSide.HOME) ? 100 : 0;
        double spd = attributes.getSpeed() * SPEED_SCALE;

        switch (state) {
            case WITH_BALL -> {
                double distToGoal = Math.abs(this.position.x - targetGoalX);
                // Chance de chute um pouco maior em distância razoável (partida mais viva)
                if (distToGoal < 28 && Math.random() < attributes.getShooting() * 0.04) {
                    Vector2D shotDir = Vector2D.sub(
                        new Vector2D(targetGoalX, 50 + (Math.random() * 8 - 4)),
                        this.position
                    );
                    shotDir.normalize();
                    // Força de chute reduzida
                    shotDir.mult(1.4 + attributes.getShooting() * 0.6);
                    ball.kick(shotDir);
                    this.cooldownTicks = 12;
                    return;
                }
                Player bestTeammate = engine.findBestPassOption(this);
                if (bestTeammate != null && Math.random() < attributes.getPassing() * 0.025) {
                    Vector2D passDir = Vector2D.sub(bestTeammate.getPosition(), this.position);
                    passDir.normalize();
                    passDir.mult(1.1 + attributes.getPassing() * 0.5);
                    ball.kick(passDir);
                    this.cooldownTicks = 8;
                    return;
                }
                steerTowards(new Vector2D(targetGoalX, 50), spd * 0.65);
            }
            case DEFENDING -> {
                if (ball.getOwner() != null) {
                    if (this.position.distance(ball.getOwner().getPosition()) < 3.2
                            && Math.random() < attributes.getTackling() * 0.08) {
                        ball.getOwner().setCooldownTicks(22);
                        ball.setOwner(this);
                    } else {
                        steerTowards(ball.getPosition(), spd * 0.85);
                    }
                }
            }
            case SUPPORTING -> {
                if (ball.getOwner() == null) {
                    steerTowards(ball.getPosition(), spd);
                } else {
                    steerTowards(
                        new Vector2D(
                            homePosition.x + (team == Enums.TeamSide.HOME ? 12 : -12),
                            homePosition.y
                        ),
                        spd * 0.7
                    );
                }
            }
            case IDLE -> {
                if (role == Enums.PlayerRole.GOALKEEPER) {
                    steerTowards(
                        new Vector2D(
                            team == Enums.TeamSide.HOME ? 3 : 97,
                            Math.max(40, Math.min(60, ball.getPosition().y))
                        ),
                        spd * 0.9
                    );
                } else {
                    steerTowards(homePosition, spd * 0.5);
                }
            }
            default -> { }
        }
    }

    private void steerTowards(Vector2D target, double maxSpeed) {
        Vector2D desired = Vector2D.sub(target, this.position);
        double d = desired.mag();
        if (d > 0.4) {
            desired.normalize();
            desired.mult(d < 6.0 ? maxSpeed * (d / 6.0) : maxSpeed);
            Vector2D steer = Vector2D.sub(desired, this.velocity);
            steer.limit(attributes.getAcceleration() * ACCEL_SCALE);
            this.velocity.add(steer);
        }
    }

    private void applyFriction() {
        this.velocity.mult(0.88);
    }

    private void keepInBounds() {
        if (this.position.x < 2) this.position.x = 2;
        if (this.position.x > 98) this.position.x = 98;
        if (this.position.y < 2) this.position.y = 2;
        if (this.position.y > 98) this.position.y = 98;
    }
}