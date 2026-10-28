package com.berimbet.model;

import lombok.Data;

@Data
public class Ball {
    private Vector2D position = new Vector2D(50, 50);
    private Vector2D velocity = new Vector2D(0, 0);
    private Player owner = null;

    public void updatePhysics() {
        if (owner != null) {
            double offsetX = owner.getTeam() == Enums.TeamSide.HOME ? 1.4 : -1.4;
            this.position.x = owner.getPosition().x + offsetX;
            this.position.y = owner.getPosition().y;
            this.velocity.x = 0;
            this.velocity.y = 0;
            return;
        }

        this.position.add(this.velocity);
        this.velocity.mult(0.935);

        // Laterais
        if (this.position.y <= 2) { this.position.y = 2; this.velocity.y *= -0.72; }
        if (this.position.y >= 98) { this.position.y = 98; this.velocity.y *= -0.72; }

        // Fundo — fora do gol rebate; dentro do gol passa (gol detectado no engine)
        boolean outsideGoalMouth = this.position.y < 39 || this.position.y > 61;
        if (this.position.x <= 1.5 && outsideGoalMouth) {
            this.position.x = 1.5;
            this.velocity.x *= -0.72;
        }
        if (this.position.x >= 98.5 && outsideGoalMouth) {
            this.position.x = 98.5;
            this.velocity.x *= -0.72;
        }

        if (this.velocity.mag() < 0.04) {
            this.velocity.x = 0;
            this.velocity.y = 0;
        }
    }

    public void kick(Vector2D force) {
        this.owner = null;
        this.velocity.x = force.x;
        this.velocity.y = force.y;
    }
}