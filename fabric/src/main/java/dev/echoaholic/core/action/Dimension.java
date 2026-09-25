package dev.echoaholic.core.action;

import java.util.Objects;

/** The player arrived in another dimension at (x, y, z), e.g. dimensionId "minecraft:the_nether". */
public record Dimension(String dimensionId, double x, double y, double z) implements Action {
	public Dimension {
		Objects.requireNonNull(dimensionId, "dimensionId");
	}
}
