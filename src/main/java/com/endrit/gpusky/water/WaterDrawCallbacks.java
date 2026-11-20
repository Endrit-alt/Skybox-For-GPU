package com.endrit.gpusky.water;

import java.util.Set;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Decorates the public callbacks, including existing wrappers; never replaces the GPU
 * implementation. Every DrawCallbacks method is forwarded explicitly (no reflection,
 * which Plugin Hub does not allow). If the API gains new callbacks, add them here.
 */
final class WaterDrawCallbacks implements DrawCallbacks
{
	interface Listener
	{
		void afterScene(Scene scene, float cameraX, float cameraY, float cameraZ);
		void drawZone(DrawCallbacks delegate, Projection projection, Scene scene, int x, int z);
		default void beforeAlpha() { }
		/** Before the top-level scene's frame starts (before the GPU plugin clears its scene buffers). */
		default void beforeScene() { }
		default void drawAlpha(DrawCallbacks delegate, Projection projection, Scene scene, int level, int x, int z)
		{
			delegate.drawZoneAlpha(projection, scene, level, x, z);
		}
		/** A callback threw; this wrapper is already pass-through. Must not throw. */
		default void callbackFailed(String during, Throwable ex) { }
	}

	final DrawCallbacks delegate;
	/** The installed callbacks object (this wrapper). */
	final DrawCallbacks proxy = this;
	private volatile Listener listener;

	WaterDrawCallbacks(DrawCallbacks delegate, Listener listener)
	{
		this.delegate = delegate;
		this.listener = listener;
	}

	void deactivate() { listener = null; }

	/**
	 * Water errors must not escape into the GPU plugin's frame. The wrapper becomes
	 * pass-through at once; the listener switches water off and cleans up later.
	 */
	private void failed(Listener current, String during, Throwable ex)
	{
		deactivate();
		try
		{
			current.callbackFailed(during, ex);
		}
		catch (Throwable ignored)
		{
			// Already pass-through; nothing more can be done here.
		}
	}

	@Override
	public String toString()
	{
		return "WaterDrawCallbacks(" + delegate + ")";
	}

	// ---------- Callbacks Water hooks into ----------

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		Listener current = listener;
		if (current == null)
		{
			delegate.drawZoneOpaque(entityProjection, scene, zx, zz);
			return;
		}
		try
		{
			current.drawZone(delegate, entityProjection, scene, zx, zz);
		}
		catch (Throwable ex)
		{
			// This zone may be missing for one frame; later frames pass straight through.
			failed(current, "drawing an opaque zone", ex);
		}
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		Listener current = listener;
		if (current == null)
		{
			delegate.drawZoneAlpha(entityProjection, scene, level, zx, zz);
			return;
		}
		// Fallback for callback chains that do not forward PRE_PASS_ALPHA. Alpha
		// objects do not write depth, so water MUST already be composited here.
		try
		{
			current.beforeAlpha();
			current.drawAlpha(delegate, entityProjection, scene, level, zx, zz);
		}
		catch (Throwable ex)
		{
			failed(current, "drawing an alpha zone", ex);
		}
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		beforeScene(scene);
		delegate.preSceneDraw(scene, entityProjection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
			minLevel, level, maxLevel, hideRoofIds);
		afterScene(scene, cameraX, cameraY, cameraZ);
	}

	private void beforeScene(Scene scene)
	{
		Listener current = listener;
		if (current == null || scene.getWorldViewId() != WorldView.TOPLEVEL) return;
		try
		{
			current.beforeScene();
		}
		catch (Throwable ex)
		{
			failed(current, "preparing water before the scene", ex);
		}
	}

	private void afterScene(Scene scene, float cameraX, float cameraY, float cameraZ)
	{
		Listener current = listener;
		if (current == null) return;
		try
		{
			current.afterScene(scene, cameraX, cameraY, cameraZ);
		}
		catch (Throwable ex)
		{
			failed(current, "preparing the scene", ex);
		}
	}

	@Override
	@SuppressWarnings("deprecation")
	public void preSceneDraw(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		beforeScene(scene);
		delegate.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
			minLevel, level, maxLevel, hideRoofIds);
		afterScene(scene, cameraX, cameraY, cameraZ);
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass)
	{
		delegate.drawPass(entityProjection, scene, pass);
		Listener current = listener;
		if (current != null && pass == DrawCallbacks.PRE_PASS_ALPHA)
		{
			// Let the GPU finish alpha-pass setup (including world-view transforms),
			// then draw water before any transparent faces are submitted.
			try
			{
				current.beforeAlpha();
			}
			catch (Throwable ex)
			{
				failed(current, "drawing water before the alpha pass", ex);
			}
		}
	}

	// ---------- Plain forwarding ----------

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		delegate.draw(projection, scene, renderable, orientation, x, y, z, hash);
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ)
	{
		delegate.drawScenePaint(scene, paint, plane, tileX, tileZ);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ)
	{
		delegate.drawSceneTileModel(scene, model, tileX, tileZ);
	}

	@Override
	public void draw(int overlayColor)
	{
		delegate.draw(overlayColor);
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
	{
		delegate.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
	}

	@Override
	public void postDrawScene()
	{
		delegate.postDrawScene();
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		delegate.animate(texture, diff);
	}

	@Override
	public void loadScene(Scene scene)
	{
		delegate.loadScene(scene);
	}

	@Override
	public void swapScene(Scene scene)
	{
		delegate.swapScene(scene);
	}

	@Override
	public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos,
		int cameraX, int cameraY, int cameraZ, int plane, int msx, int msy)
	{
		return delegate.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx, msy);
	}

	@Override
	public boolean zoneInFrustum(int zoneX, int zoneZ, int maxY, int minY)
	{
		return delegate.zoneInFrustum(zoneX, zoneZ, maxY, minY);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		delegate.loadScene(worldView, scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		delegate.despawnWorldView(worldView);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		delegate.postSceneDraw(scene);
	}

	@Override
	public void drawDynamic(Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m,
		int orient, int x, int y, int z)
	{
		delegate.drawDynamic(worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject,
		Renderable r, Model m, int orient, int x, int y, int z)
	{
		delegate.drawDynamic(renderThreadId, worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		delegate.drawTemp(worldProjection, scene, gameObject, m, orient, x, y, z);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		delegate.invalidateZone(scene, zx, zz);
	}
}
