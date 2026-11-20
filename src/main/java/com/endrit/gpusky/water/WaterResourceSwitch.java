package com.endrit.gpusky.water;

/**
 * Keeps Water's large resources only while animated water is on. Client thread only, and
 * only called while the GPU context is current. The GPU extension itself stays registered.
 */
final class WaterResourceSwitch
{
	private final Runnable create;
	private final Runnable release;
	private boolean held;

	WaterResourceSwitch(Runnable create, Runnable release)
	{
		this.create = create;
		this.release = release;
	}

	/** Called every frame with the current setting; acts only when it changes. */
	void sync(boolean enabled)
	{
		if (enabled == held) return;
		if (enabled)
		{
			create.run();
			held = true;
		}
		else
		{
			held = false;
			release.run();
		}
	}

	/** The context was destroyed, taking the resources with it; the next enabled frame creates them again. */
	void contextLost()
	{
		held = false;
	}

	boolean held()
	{
		return held;
	}
}
