package com.endrit.gpusky;

import com.endrit.gpusky.GpuSkyExtensionConfig.SkyboxImage;
import com.endrit.gpusky.GpuSkyExtensionConfig.TextureResolution;
import com.endrit.gpusky.GpuSkyExtensionConfig.WeatherEffect;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Header-only estimate of the preset textures, including their RGBA8 mip chains. */
final class SkyboxMemoryEstimate
{
	private static final Logger log = LoggerFactory.getLogger(SkyboxMemoryEstimate.class);
	private final EnumMap<TextureResolution, Long> bytes = new EnumMap<>(TextureResolution.class);
	private int presets;
	private boolean incomplete;

	static SkyboxMemoryEstimate read(Filepath directory)
	{
		SkyboxMemoryEstimate result = new SkyboxMemoryEstimate();
		if (directory == null) return result;
		Set<String> filenames = new HashSet<>();
		for (SkyboxImage image : SkyboxImage.values())
		{
			String filename = image.resolveFilename(WeatherEffect.DISABLED);
			if (filename == null || !filenames.add(filename)) continue;
			if (Thread.currentThread().isInterrupted()) throw new CancellationException();
			try
			{
				Filepath path = directory.joinSegment("weather").joinSegment("presets").joinSegment(filename);
				if (!path.isFile()) continue; // The preloader also skips missing presets.
				result.add(path);
			}
			catch (IOException | RuntimeException ex)
			{
				result.incomplete = true;
				log.debug("Unable to estimate skybox memory for {}", filename, ex);
			}
		}
		return result;
	}

	private void add(Filepath path) throws IOException
	{
		try (InputStream bytes = path.openInputStream();
			ImageInputStream input = new MemoryCacheImageInputStream(bytes))
		{
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) throw new IOException("Unsupported image header");
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				for (TextureResolution resolution : TextureResolution.values())
				{
					long size = textureBytes(width, height, resolution.maxWidth());
					this.bytes.merge(resolution, size, Long::sum);
				}
				presets++;
			}
			finally
			{
				reader.dispose();
			}
		}
	}

	static long textureBytes(int width, int height, int maxWidth) throws IOException
	{
		int sampling = ImageLoader.subsampling(width, height, maxWidth);
		width = (width + sampling - 1) / sampling;
		height = (height + sampling - 1) / sampling;
		long total = 0;
		while (true)
		{
			total += (long) width * height * 4;
			if (width == 1 && height == 1) return total;
			width = Math.max(1, width / 2);
			height = Math.max(1, height / 2);
		}
	}

	String label(TextureResolution resolution)
	{
		if (presets == 0 || incomplete) return "Unavailable";
		return formatGigabytes(bytes.getOrDefault(resolution, 0L));
	}

	static String formatGigabytes(long bytes)
	{
		double gb = bytes / 1_000_000_000d;
		return gb < 0.01 ? "<0.01 GB" : String.format(Locale.ROOT, "~%.2f GB", gb);
	}

	int presetCount()
	{
		return presets;
	}
}
