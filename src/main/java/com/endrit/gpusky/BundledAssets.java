package com.endrit.gpusky;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The skybox presets and weather textures ship inside the plugin (resources under
 * {@code assets/}, listed in {@code assets/index.txt}). On first use each missing file
 * is copied into the plugin's data folder, where the loaders read it. Existing files
 * are never overwritten, so a user's own edits/replacements are kept.
 */
final class BundledAssets
{
	private static final Logger log = LoggerFactory.getLogger(BundledAssets.class);
	private static final String ROOT = "assets/";

	private BundledAssets()
	{
	}

	/** Copies every bundled asset that is missing from {@code directory}. Asset-worker thread only. */
	static void install(Filepath directory)
	{
		int copied = 0;
		for (String relative : index())
		{
			try
			{
				Filepath target = directory;
				for (String segment : relative.split("/"))
				{
					target = target.joinSegment(segment);
				}
				if (target.exists()) continue;
				target.getParent().createDirectories();
				Filepath temporary = target.getParent().createTempFile("bundled-", ".tmp");
				try
				{
					try (InputStream input = BundledAssets.class.getResourceAsStream(ROOT + relative);
						OutputStream output = temporary.openOutputStream())
					{
						if (input == null) throw new IOException("missing bundled asset " + relative);
						input.transferTo(output);
					}
					try
					{
						temporary.moveTo(target, StandardCopyOption.ATOMIC_MOVE);
					}
					catch (AtomicMoveNotSupportedException ex)
					{
						temporary.moveTo(target);
					}
					copied++;
				}
				finally
				{
					temporary.deleteIfExists();
				}
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Unable to install bundled skybox asset {}", relative, ex);
			}
		}
		if (copied > 0) log.info("Installed {} bundled skybox/weather assets", copied);
	}

	private static List<String> index()
	{
		List<String> entries = new ArrayList<>();
		try (InputStream input = BundledAssets.class.getResourceAsStream(ROOT + "index.txt"))
		{
			if (input == null) return entries;
			BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null)
			{
				line = line.trim();
				// Only plain relative paths inside the bundle.
				if (!line.isEmpty() && !line.startsWith("/") && !line.contains("..") && !line.contains("\\")) entries.add(line);
			}
		}
		catch (IOException ex)
		{
			log.warn("Unable to read the bundled asset index", ex);
		}
		return entries;
	}
}
