package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ImageLoaderTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void reducedModeKeepsExistingSubsamplingAndDoesNotUpscale() throws Exception
	{
		assertEquals(2, ImageLoader.subsampling(8192, 4096, true));
		assertEquals(3, ImageLoader.subsampling(8200, 4100, true));
		assertEquals(1, ImageLoader.subsampling(2048, 1024, true));
		assertEquals(1, ImageLoader.subsampling(8192, 4096, false));
		assertEquals(4, ImageLoader.subsampling(8192, 4096, 2048));
		assertEquals(2, ImageLoader.subsampling(4096, 2048, 2048));
		assertEquals(1, ImageLoader.subsampling(8192, 4096, 0));
	}

	@Test
	public void rejectsOverflowSizedHeadersAndAllocations()
	{
		assertThrows(IOException.class, () -> ImageLoader.subsampling(Integer.MAX_VALUE, 1, true));
		assertThrows(IOException.class, () -> ImageLoader.subsampling(1, -1, false));
		assertThrows(IOException.class, () -> ImageLoader.checkDimensions(Long.MAX_VALUE, 2, 4));
		assertThrows(IOException.class, () -> ImageLoader.checkDimensions(16384, 16384, 8));
		assertThrows(IOException.class, () -> ImageLoader.checkWorkingBytes(ImageLoader.MAX_WORKING_BYTES + 1));
	}

	@Test
	public void decodesPixelsWithoutChangingSmallImageDimensions() throws Exception
	{
		Path path = temporary.newFile("sky.png").toPath();
		BufferedImage source = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
		source.setRGB(12, 14, 0xff336699);
		ImageIO.write(source, "png", path.toFile());
		Filepath imagePath = Filepath.Unchecked.getRooted(path);
		ImageLoader.validate(imagePath, false);
		for (boolean reduced : new boolean[]{false, true})
		{
			BufferedImage decoded = ImageLoader.read(imagePath, reduced, 8);
			assertEquals(64, decoded.getWidth());
			assertEquals(32, decoded.getHeight());
			assertEquals(source.getRGB(12, 14), decoded.getRGB(12, 14));
		}
	}

	@Test
	public void corruptImportIsRejected() throws Exception
	{
		Path path = temporary.newFile("broken.jpg").toPath();
		Files.write(path, new byte[]{1, 2, 3, 4});
		Filepath imagePath = Filepath.Unchecked.getRooted(path);
		assertThrows(IOException.class, () -> ImageLoader.validate(imagePath, true));
		assertThrows(IOException.class, () -> ImageLoader.read(imagePath, false, 8));
	}
}
