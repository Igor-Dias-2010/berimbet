package com.berimbet.model;

public class Vector2D {
    public double x, y;
    public Vector2D(double x, double y) { this.x = x; this.y = y; }
    public void add(Vector2D v) { this.x += v.x; this.y += v.y; }
    public void sub(Vector2D v) { this.x -= v.x; this.y -= v.y; }
    public void mult(double scalar) { this.x *= scalar; this.y *= scalar; }
    public double mag() { return Math.sqrt(x * x + y * y); }
    public void normalize() { double m = mag(); if (m != 0 && m != 1) mult(1 / m); }
    public void limit(double max) { if (mag() > max) { normalize(); mult(max); } }
    public double distance(Vector2D v) { double dx = this.x - v.x, dy = this.y - v.y; return Math.sqrt(dx * dx + dy * dy); }
    public static Vector2D sub(Vector2D v1, Vector2D v2) { return new Vector2D(v1.x - v2.x, v1.y - v2.y); }
}