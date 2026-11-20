package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
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

	/**
	 * Working memory for a panorama that decodes to 3-byte BGR (the JPEG reader's only
	 * destination type). Measured on the bundled 8192x4096 presets under -Xmx768m: the
	 * decoded image plus decoder buffers peak at about 4.9-5.6 bytes per pixel, summed over
	 * the heap pools (an upper bound that includes collectable garbage). OpenGlContext
	 * uploads that image's own bytes, so no second full-size copy is made.
	 */
	static final int PANORAMA_BYTES_PER_PIXEL = 5;
	/** Other image types (for example PNG with alpha) still go through a 4-byte upload copy. */
	static final int PANORAMA_COPY_BYTES_PER_PIXEL = 8;
	/** Width used instead when a larger panorama does not fit the working-memory budget. */
	static final int MEMORY_FALLBACK_WIDTH = 4096;

	/**
	 * Reads a sky panorama at most {@code maxWidth} wide (0 = original size). When that
	 * does not fit the working-memory budget, it loads at {@link #MEMORY_FALLBACK_WIDTH}
	 * instead and reports it through {@code reduced}, rather than failing.
	 */
	static BufferedImage readPanorama(Filepath path, int maxWidth, Consumer<String> reduced) throws IOException
	{
		return readPanorama(path, maxWidth, reduced, false);
	}

	static void validatePanorama(Filepath path, int maxWidth) throws IOException
	{
		readPanorama(path, maxWidth, message -> { }, true);
	}

	private static BufferedImage readPanorama(Filepath path, int maxWidth, Consumer<String> reduced,
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
				int bytesPerPixel = decodesToBgr(reader) ? PANORAMA_BYTES_PER_PIXEL : PANORAMA_COPY_BYTES_PER_PIXEL;
				int sampling = panoramaSampling(width, height, maxWidth, bytesPerPixel, reduced, path);
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

	/** Subsampling for a panorama, falling back to {@link #MEMORY_FALLBACK_WIDTH} when memory is short. */
	static int panoramaSampling(int width, int height, int maxWidth, int bytesPerPixel,
		Consumer<String> reduced, Object source) throws IOException
	{
		int sampling = subsampling(width, height, maxWidth);
		try
		{
			checkDimensions(divideUp(width, sampling), divideUp(height, sampling), bytesPerPixel);
			return sampling;
		}
		catch (IOException tooLarge)
		{
			int fallback = subsampling(width, height, MEMORY_FALLBACK_WIDTH);
			if (fallback <= sampling)
			{
				throw tooLarge;
			}
			checkDimensions(divideUp(width, fallback), divideUp(height, fallback), bytesPerPixel);
			reduced.accept(source + " (" + width + "x" + height + ") is too large to load at this resolution with "
				+ "RuneLite's memory limit (a " + workingBudget() / (1024 * 1024) + " MiB working budget); loading it at "
				+ divideUp(width, fallback) + "x" + divideUp(height, fallback) + " instead");
			return fallback;
		}
	}

	/** Whether a full-size panorama of these dimensions fits the working-memory budget. */
	static boolean panoramaFits(int width, int height)
	{
		return (long) width * height * PANORAMA_BYTES_PER_PIXEL <= workingBudget();
	}

	private static boolean decodesToBgr(ImageReader reader) throws IOException
	{
		// The first type is the reader's default destination.
		Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
		return types.hasNext() && types.next().getBufferedImageType() == BufferedImage.TYPE_3BYTE_BGR;
	}

	private static long divideUp(int value, int divisor)
	{
		return (value + (long) divisor - 1) / divisor;
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

	static long workingBudget()
	{
		return Math.min(MAX_WORKING_BYTES, Runtime.getRuntime().maxMemory() / 4);
	}

	static void checkWorkingBytes(long bytes) throws IOException
	{
		long budget = workingBudget();
		if (bytes < 0 || bytes > budget)
		{
			throw new IOException("Image/atlas working memory exceeds the " + budget / (1024 * 1024)
				+ " MiB safety limit; use smaller images or fewer frames");
		}
	}
}
