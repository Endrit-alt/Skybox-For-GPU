package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.runelite.client.util.Filepath;

/** Validates image headers and working allocations before decoding pixels. */
final class ImageLoader
{
	static final int MAX_DIMENSION = 16384;
	static final long MAX_WORKING_BYTES = 256L * 1024 * 1024;

	private ImageLoader()
	{
	}

	/** {@code reduced} = load at most 4K wide (the former default); otherwise the original size. */
	static BufferedImage read(Filepath path, boolean reduced, int workingBytesPerPixel) throws IOException
	{
		return read(path, reduced ? 4096 : 0, workingBytesPerPixel, false);
	}

	/** {@code maxWidth}: largest width to load (2:1 panorama), 0 = original size. */
	static BufferedImage read(Filepath path, int maxWidth, int workingBytesPerPixel) throws IOException
	{
		return read(path, maxWidth, workingBytesPerPixel, false);
	}

	/** {@code reduced} = validate as a 4K load (the former default); otherwise the original size. */
	static void validate(Filepath path, boolean reduced) throws IOException
	{
		validate(path, reduced ? 4096 : 0);
	}

	static void validate(Filepath path, int maxWidth) throws IOException
	{
		read(path, maxWidth, 8, true);
	}

	private static BufferedImage read(Filepath path, int maxWidth, int workingBytesPerPixel,
		boolean headerOnly) throws IOException
	{
		try (InputStream bytes = path.openInputStream();
			ImageInputStream input = new MemoryCacheImageInputStream(bytes))
		{
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext())
			{
				throw new IOException("Unsupported or corrupt image: " + path);
			}
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				int sampling = subsampling(width, height, maxWidth);
				checkDimensions((width + (long) sampling - 1) / sampling,
					(height + (long) sampling - 1) / sampling, workingBytesPerPixel);
				if (headerOnly)
				{
					return null;
				}
				ImageReadParam parameters = reader.getDefaultReadParam();
				parameters.setSourceSubsampling(sampling, sampling, 0, 0);
				BufferedImage image = reader.read(0, parameters);
				if (image == null)
				{
					throw new IOException("Unable to decode image: " + path);
				}
				return image;
			}
			finally
			{
				reader.dispose();
			}
		}
	}

	static int subsampling(int width, int height, boolean reduced) throws IOException
	{
		return subsampling(width, height, reduced ? 4096 : 0);
	}

	static int subsampling(int width, int height, int maxWidth) throws IOException
	{
		// Bound even subsampled source headers; malformed images can otherwise stress a decoder.
		if (width <= 0 || height <= 0 || width > 65536 || height > 65536
			|| (long) width * height > 268435456L)
		{
			throw new IOException("Unsupported source dimensions: " + width + "x" + height);
		}
		if (maxWidth <= 0) return 1;
		long maxHeight = maxWidth / 2;
		return (int) Math.max((width + maxWidth - 1L) / maxWidth, (height + maxHeight - 1L) / maxHeight);
	}

	static void checkDimensions(long width, long height, int workingBytesPerPixel) throws IOException
	{
		if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION)
		{
			throw new IOException("Unsupported image/atlas dimensions: " + width + "x" + height);
		}
		checkWorkingBytes(width * height * workingBytesPerPixel);
	}

	static void checkWorkingBytes(long bytes) throws IOException
	{
		long budget = Math.min(MAX_WORKING_BYTES, Runtime.getRuntime().maxMemory() / 4);
		if (bytes < 0 || bytes > budget)
		{
			throw new IOException("Image/atlas working memory exceeds the " + budget / (1024 * 1024)
				+ " MiB safety limit; use smaller images or fewer frames");
		}
	}
}
