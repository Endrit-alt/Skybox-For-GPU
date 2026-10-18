package com.endrit.gpusky;

import net.runelite.client.plugins.gpu.Mat4;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherBillboardTest
{
	@Test
	public void cameraFacingSpritesStayScreenParallelAtEveryYawAndPitch()
	{
		for (int yawDegrees = 0; yawDegrees < 360; yawDegrees += 30)
		{
			for (int pitchDegrees : new int[]{0, 22, 45, 67, 90})
			{
				float yaw = (float) Math.toRadians(yawDegrees);
				float pitch = (float) Math.toRadians(pitchDegrees);
				WeatherRenderer.Billboard billboard = WeatherRenderer.cameraBillboard(yaw, pitch);
				float[] view = Mat4.rotateX(pitch);
				Mat4.mul(view, Mat4.rotateY(yaw));
				assertArrayEquals(new float[]{1, 0, 0}, transformDirection(view,
					billboard.rightX, billboard.rightY, billboard.rightZ), 0.0001f);
				assertArrayEquals(new float[]{0, 1, 0}, transformDirection(view,
					billboard.upX, billboard.upY, billboard.upZ), 0.0001f);
			}
		}
	}

	private static float[] transformDirection(float[] matrix, float x, float y, float z)
	{
		return new float[]{
			matrix[0] * x + matrix[4] * y + matrix[8] * z,
			matrix[1] * x + matrix[5] * y + matrix[9] * z,
			matrix[2] * x + matrix[6] * y + matrix[10] * z
		};
	}
}
