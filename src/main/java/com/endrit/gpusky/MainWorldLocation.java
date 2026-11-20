package com.endrit.gpusky;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * The player's position in the main (top-level) world. On a boat the player's local position
 * belongs to the boat's own world view, so it is converted through that boat's world entity,
 * as RuneLite's skybox plugin does (runelite/runelite PR #20642). Client thread only.
 */
public final class MainWorldLocation
{
	private MainWorldLocation()
	{
	}

	/** The local player's main-world position, or null without a player or position. */
	public static LocalPoint ofPlayer(Client client)
	{
		Player player = client.getLocalPlayer();
		return player == null ? null : toMainWorld(client, player.getLocalLocation());
	}

	/**
	 * A local point in the main world. Points already there are returned as they are; if the
	 * boat holding the point cannot be found (for a moment while boarding), the point is
	 * returned unchanged.
	 */
	static LocalPoint toMainWorld(Client client, LocalPoint point)
	{
		if (point == null || point.getWorldView() == WorldView.TOPLEVEL)
		{
			return point;
		}
		WorldView top = client.getTopLevelWorldView();
		WorldEntity entity = top == null ? null : top.worldEntities().byIndex(point.getWorldView());
		LocalPoint converted = entity == null ? null : entity.transformToMainWorld(point);
		return converted == null ? point : converted;
	}
}
