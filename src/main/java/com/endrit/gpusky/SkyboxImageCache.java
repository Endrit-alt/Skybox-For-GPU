package com.endrit.gpusky;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Worker-owned signatures and last-good resident metadata, without decoded pixels. */
final class SkyboxImageCache
{
	interface Loader
	{
		SkyboxAsset read() throws IOException;
	}

	/** A load or upload that threw is tried this many times in all, then left until the file changes. */
	static final int MAX_ATTEMPTS = 3;
	private static final long[] RETRY_NANOS = {5_000_000_000L, 30_000_000_000L};

	private final Map<String, Entry> entries = new HashMap<>();
	private final LongSupplier clock;

	SkyboxImageCache()
	{
		this(System::nanoTime);
	}

	SkyboxImageCache(LongSupplier clock)
	{
		this.clock = clock;
	}

	SkyboxAsset read(String key, String signature, Loader loader, Consumer<IOException> failure)
	{
		long now = clock.getAsLong();
		Entry old = entries.get(key);
		boolean same = old != null && old.signature.equals(signature);
		if (same && !old.retryDue(now))
		{
			return old.image;
		}
		// Keep showing the last working image; a failure is never recorded as a success.
		SkyboxAsset image = old == null ? null : old.image;
		int attempts = same ? old.attempts : 0;
		try
		{
			SkyboxAsset replacement = loader.read();
			if (replacement == null)
			{
				// Missing or not an image: only a changed file can help, so no retry.
				attempts = MAX_ATTEMPTS - 1;
				throw new IOException("Image is missing or cannot be decoded: " + key);
			}
			entries.put(key, new Entry(signature, replacement, 0, 0));
			return replacement;
		}
		catch (IOException ex)
		{
			failure.accept(ex);
		}
		attempts++;
		long retryAt = attempts < MAX_ATTEMPTS ? now + RETRY_NANOS[attempts - 1] : 0;
		entries.put(key, new Entry(signature, image, attempts, retryAt));
		return image;
	}

	/** A failed entry is due for one of its bounded retries. */
	boolean retryDue()
	{
		long now = clock.getAsLong();
		for (Entry entry : entries.values())
		{
			if (entry.retryDue(now))
			{
				return true;
			}
		}
		return false;
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
		/** Failed attempts for this signature; 0 after a success. */
		private final int attempts;
		private final long retryAt;

		private Entry(String signature, SkyboxAsset image, int attempts, long retryAt)
		{
			this.signature = signature;
			this.image = image;
			this.attempts = attempts;
			this.retryAt = retryAt;
		}

		private boolean retryDue(long now)
		{
			return attempts > 0 && attempts < MAX_ATTEMPTS && now - retryAt >= 0;
		}
	}
}
