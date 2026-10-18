package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** One producer, one client-thread upload batch, and at most one decoded panorama in flight. */
final class SkyboxUploadBatch
{
	private static final int TIMEOUT_SECONDS = 30;
	private static final Upload END = new Upload(null, null);
	private final ArrayBlockingQueue<Upload> queue = new ArrayBlockingQueue<>(1);
	private final AtomicReference<Upload> pending = new AtomicReference<>();
	private volatile boolean cancelled;

	void upload(SkyboxAsset asset, BufferedImage image) throws IOException
	{
		submit(new Upload(asset, image));
	}

	void execute(Runnable action) throws IOException
	{
		Upload next = new Upload(null, null);
		next.action = action;
		submit(next);
	}

	private void submit(Upload next) throws IOException
	{
		checkActive();
		if (!pending.compareAndSet(null, next))
		{
			throw new IllegalStateException("A skybox upload is already pending");
		}
		try
		{
			checkActive();
			if (!queue.offer(next))
			{
				checkActive();
				throw new IllegalStateException("Skybox upload queue is occupied");
			}
			next.complete.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			checkActive();
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			cancel();
			throw new CancellationException("Skybox upload interrupted");
		}
		catch (TimeoutException ex)
		{
			cancel();
			throw new CancellationException("Skybox upload timed out");
		}
		catch (ExecutionException ex)
		{
			throw new IOException("Unable to upload skybox texture", ex.getCause());
		}
		finally
		{
			next.image = null;
			pending.compareAndSet(next, null);
		}
	}

	void drain(BooleanSupplier valid, BiConsumer<SkyboxAsset, BufferedImage> upload)
	{
		try
		{
			while (!cancelled)
			{
				Upload next = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				if (next == END)
				{
					return;
				}
				if (next == null || cancelled || !valid.getAsBoolean())
				{
					cancel();
					return;
				}
				RuntimeException failure = null;
				try
				{
					if (next.action != null)
					{
						next.action.run();
					}
					else
					{
						upload.accept(next.asset, next.image);
					}
				}
				catch (RuntimeException ex)
				{
					failure = ex;
				}
				finally
				{
					next.image = null;
					next.action = null;
				}
				if (failure == null)
				{
					next.complete.complete(null);
				}
				else
				{
					next.complete.completeExceptionally(failure);
				}
			}
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
		}
		finally
		{
			cancel();
		}
	}

	void finish()
	{
		queue.offer(END);
	}

	void cancel()
	{
		cancelled = true;
		Upload next = pending.getAndSet(null);
		if (next != null)
		{
			next.image = null;
			next.complete.cancel(false);
		}
		queue.clear();
		queue.offer(END);
	}

	void checkActive()
	{
		if (cancelled)
		{
			throw new CancellationException("Skybox upload batch ended");
		}
	}

	private static final class Upload
	{
		final SkyboxAsset asset;
		final CompletableFuture<Void> complete = new CompletableFuture<>();
		volatile BufferedImage image;
		Runnable action;

		Upload(SkyboxAsset asset, BufferedImage image)
		{
			this.asset = asset;
			this.image = image;
		}
	}
}
