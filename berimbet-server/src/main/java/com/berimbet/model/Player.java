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

    public Player(int id, Enums.TeamSide team, Enums.PlayerRole role, Vector2D startPos) {
        this.id = id; this.team = team; this.role = role;
        this.homePosition = new Vector2D(startPos.x, startPos.y); 
        this.position = new Vector2D(startPos.x, startPos.y);
        this.velocity = new Vector2D(0, 0); 
        this.state = Enums.PlayerState.IDLE; 
        this.attributes = PlayerAttributes.generateForRole(role);
    }

    public void update(MatchEngine engine) {
        if (cooldownTicks > 0) { cooldownTicks--; applyFriction(); return; }
        decideState(engine); 
        executeState(engine);
        this.position.add(this.velocity); 
        applyFriction(); 
        keepInBounds();
    }

    private void decideState(MatchEngine engine) {
        Ball ball = engine.getBall();
        if (role == Enums.PlayerRole.GOALKEEPER) { this.state = Enums.PlayerState.IDLE; return; }
        if (ball.getOwner() == this) this.state = Enums.PlayerState.WITH_BALL;
        else if (ball.getOwner() != null && ball.getOwner().getTeam() == this.team) this.state = Enums.PlayerState.SUPPORTING;
        else if (ball.getOwner() != null && ball.getOwner().getTeam() != this.team) this.state = Enums.PlayerState.DEFENDING;
        else { 
            Player closest = engine.getClosestPlayerToBall(this.team); 
            this.state = (closest == this) ? Enums.PlayerState.SUPPORTING : Enums.PlayerState.IDLE; 
        }
    }

    private void executeState(MatchEngine engine) {
        Ball ball = engine.getBall(); 
        double targetGoalX = (team == Enums.TeamSide.HOME) ? 100 : 0;
        switch (state) {
            case WITH_BALL -> {
                double distToGoal = Math.abs(this.position.x - targetGoalX);
                if (distToGoal < 30 && Math.random() < attributes.getShooting() * 0.05) {
                    Vector2D shotDir = Vector2D.sub(new Vector2D(targetGoalX, 50 + (Math.random() * 10 - 5)), this.position);
                    shotDir.normalize(); shotDir.mult(2.5 + attributes.getShooting());
                    ball.kick(shotDir); this.cooldownTicks = 15; return;
                }
                Player bestTeammate = engine.findBestPassOption(this);
                if (bestTeammate != null && Math.random() < attributes.getPassing() * 0.03) {
                    Vector2D passDir = Vector2D.sub(bestTeammate.getPosition(), this.position);
                    passDir.normalize(); passDir.mult(1.8 + attributes.getPassing());
                    ball.kick(passDir); this.cooldownTicks = 10; return;
                }
                steerTowards(new Vector2D(targetGoalX, 50), attributes.getSpeed() * 0.7);
            }
            case DEFENDING -> {
                if (ball.getOwner() != null) {
                    if (this.position.distance(ball.getOwner().getPosition()) < 3.0 && Math.random() < attributes.getTackling() * 0.1) {
                        ball.getOwner().setCooldownTicks(30); ball.setOwner(this);
                    } else steerTowards(ball.getPosition(), attributes.getSpeed() * 0.9);
                }
            }
            case SUPPORTING -> {
                if (ball.getOwner() == null) steerTowards(ball.getPosition(), attributes.getSpeed());
                else steerTowards(new Vector2D(homePosition.x + (team == Enums.TeamSide.HOME ? 15 : -15), homePosition.y), attributes.getSpeed() * 0.8);
            }
            case IDLE -> {
                if (role == Enums.PlayerRole.GOALKEEPER) steerTowards(new Vector2D(team == Enums.TeamSide.HOME ? 3 : 97, Math.max(40, Math.min(60, ball.getPosition().y))), attributes.getSpeed());
                else steerTowards(homePosition, attributes.getSpeed() * 0.6);
            }
        }
    }

    private void steerTowards(Vector2D target, double maxSpeed) {
        Vector2D desired = Vector2D.sub(target, this.position); double d = desired.mag();
        if (d > 0) {
            desired.normalize(); desired.mult(d < 5.0 ? maxSpeed * (d / 5.0) : maxSpeed);
            Vector2D steer = Vector2D.sub(desired, this.velocity); steer.limit(attributes.getAcceleration()); this.velocity.add(steer);
        }
    }

    private void applyFriction() { this.velocity.mult(0.90); }
    
    private void keepInBounds() {
        if (this.position.x < 2) this.position.x = 2; if (this.position.x > 98) this.position.x = 98;
        if (this.position.y < 2) this.position.y = 2; if (this.position.y > 98) this.position.y = 98;
    }
}