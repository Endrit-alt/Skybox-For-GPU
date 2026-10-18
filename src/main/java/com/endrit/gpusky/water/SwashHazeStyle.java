package com.endrit.gpusky.water;

/** Variants of the swash foam's trailing haze; the ordinal is the shader's mawHazeStyle. */
public enum SwashHazeStyle
{
	CLASSIC("Classic"),
	SOFT_VEIL("Soft veil");

	private final String name;

	SwashHazeStyle(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
