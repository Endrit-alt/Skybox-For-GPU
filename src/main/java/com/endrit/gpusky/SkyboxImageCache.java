package com.endrit.gpusky;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Worker-owned signatures and last-good resident metadata, without decoded pixels. */
final class SkyboxImageCache
{
	interface Loader
	{
		SkyboxAsset read() throws IOException;
	}

	private final Map<String, Entry> entries = new HashMap<>();

	SkyboxAsset read(String key, String signature, Loader loader, Consumer<IOException> failure)
	{
		Entry old = entries.get(key);
		if (old != null && old.signature.equals(signature))
		{
			return old.image;
		}
		SkyboxAsset image = old == null ? null : old.image;
		try
		{
			SkyboxAsset replacement = loader.read();
			if (replacement == null)
			{
				throw new IOException("Image is missing or cannot be decoded: " + key);
			}
			image = replacement;
		}
		catch (IOException ex)
		{
			failure.accept(ex);
		}
		entries.put(key, new Entry(signature, image));
		return image;
	}

	void retain(Collection<String> keys)
	{
		entries.keySet().retainAll(keys);
	}

	void clear()
	{
		entries.clear();
	}

	private static final class Entry
	{
		private final String signature;
		private final SkyboxAsset image;

		private Entry(String signature, SkyboxAsset image)
		{
			this.signature = signature;
			this.image = image;
		}
	}
}
