package dev.echoaholic.core.stream;

import dev.echoaholic.core.action.Move;

/** A move sample and the stream tick it was taken at. */
public record SampledMove(long tick, Move move) {}
