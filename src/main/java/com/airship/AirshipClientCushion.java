package com.airship;

/** The part of a cushion the client needs to draw it while the ship flies. */
public record AirshipClientCushion(double x, double y, double z, float yaw, String color) {}
