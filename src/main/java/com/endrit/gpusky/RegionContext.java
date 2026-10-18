package com.endrit.gpusky;

import net.runelite.api.GameState;

/** One client-thread snapshot; identity also invalidates in-flight region publications. */
final class RegionContext
{
	final int regionId;
	final boolean inChambersOfXeric;
	final boolean ready;

	RegionContext()
	{
		this(-1, false, false);
	}

	private RegionContext(int regionId, boolean inChambersOfXeric, boolean ready)
	{
		this.regionId = regionId;
		this.inChambersOfXeric = inChambersOfXeric;
		this.ready = ready;
	}

	RegionContext update(GameState state, int nextRegionId, boolean nextInChambers)
	{
		boolean loginScreen = state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR;
		boolean nextReady = loginScreen || state == GameState.LOGGED_IN && nextRegionId >= 0;
		int nextId = loginScreen ? -1 : nextReady ? nextRegionId : regionId;
		boolean nextCox = !loginScreen && (nextReady ? nextInChambers : inChambersOfXeric);
		if (nextId == regionId && nextCox == inChambersOfXeric && nextReady == ready)
		{
			return this;
		}
		return new RegionContext(nextId, nextCox, nextReady);
	}
}
