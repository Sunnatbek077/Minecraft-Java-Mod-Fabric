package com.sunnatbek.villagerciv.build;

/**
 * One piece of a planned fortification that has not been started yet: a stretch of wall, a gate
 * or a tower, with where it goes and which way it faces. Once work starts it becomes an ordinary
 * {@link Building}.
 */
public record WallPiece(String blueprint, int x, int z, int rotation, int sizeX, int sizeZ) {
}
