package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Owns GPU textures by lightweight identity, including both ends of a crossfade. */
final class SkyboxTextures
{
	interface Backend
	{
		int upload(BufferedImage image);
		void destroy(int texture);
	}

	private final Backend backend;
	private final Map<SkyboxAsset, Integer> textures = new HashMap<>();
	private final Set<SkyboxAsset> required = new LinkedHashSet<>();
	private SkyboxAsset current;
	private SkyboxAsset previous;

	SkyboxTextures(Backend backend)
	{
		this.backend = backend;
	}

	void require(Collection<SkyboxAsset> images)
	{
		required.clear();
		required.addAll(images);
		required.remove(null);
	}

	void upload(SkyboxAsset asset, BufferedImage image)
	{
		if (!textures.containsKey(asset))
		{
			textures.put(asset, backend.upload(image));
		}
	}

	boolean pending(Collection<SkyboxAsset> images)
	{
		return images.stream().anyMatch(image -> !textures.containsKey(image));
	}

	boolean select(SkyboxAsset image)
	{
		if (image == current || image != null && !textures.containsKey(image))
		{
			return false;
		}
		previous = image == null ? null : current;
		current = image;
		return true;
	}

	int currentTexture()
	{
		return textures.getOrDefault(current, 0);
	}

	int previousTexture()
	{
		return textures.getOrDefault(previous, currentTexture());
	}

	void finishTransition()
	{
		previous = null;
		prune();
	}

	void prune()
	{
		Iterator<Map.Entry<SkyboxAsset, Integer>> iterator = textures.entrySet().iterator();
		while (iterator.hasNext())
		{
			Map.Entry<SkyboxAsset, Integer> entry = iterator.next();
			SkyboxAsset image = entry.getKey();
			if (image != current && image != previous && !required.contains(image))
			{
				backend.destroy(entry.getValue());
				iterator.remove();
			}
		}
	}
}
