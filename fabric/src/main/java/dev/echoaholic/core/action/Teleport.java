package dev.echoaholic.core.action;

/** Same-dimension jump to (x, y, z): respawn, ender pearl, /tp. */
public record Teleport(double x, double y, double z) implements Action {}
