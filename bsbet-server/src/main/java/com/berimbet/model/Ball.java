package com.berimbet.model;

import lombok.Data;

@Data
public class Ball {
    private Vector2D position = new Vector2D(50, 50);
    private Vector2D velocity = new Vector2D(0, 0);
    private Player owner = null;

    public void updatePhysics() {
        if (owner != null) {
            double offsetX = owner.getTeam() == Enums.TeamSide.HOME ? 1.5 : -1.5;
            this.position.x = owner.getPosition().x + offsetX;
            this.position.y = owner.getPosition().y;
            this.velocity.x = 0;
            this.velocity.y = 0;
        } else {
            this.position.add(this.velocity);
            // Atrito um pouco maior → bola não voa longe demais
            this.velocity.mult(0.94);

            if (this.position.y <= 2) { this.position.y = 2; this.velocity.y *= -0.75; }
            if (this.position.y >= 98) { this.position.y = 98; this.velocity.y *= -0.75; }

            boolean isOutsideGoalY = this.position.y < 39 || this.position.y > 61;
            if (this.position.x <= 2 && isOutsideGoalY) { this.position.x = 2; this.velocity.x *= -0.75; }
            if (this.position.x >= 98 && isOutsideGoalY) { this.position.x = 98; this.velocity.x *= -0.75; }

            // Para a bola se estiver quase parada (evita micro-tremores)
            if (this.velocity.mag() < 0.05) {
                this.velocity.x = 0;
                this.velocity.y = 0;
            }
        }
    }

    public void kick(Vector2D force) {
        this.owner = null;
        this.velocity.add(force);
    }
}