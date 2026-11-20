package com.endrit.gpusky;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.runelite.api.Client;
import org.lwjgl.opengl.GL33C;

final class OpenGlContext
{
	static final int VERTEX_STRIDE_FLOATS = 13;
	// Units 0..5: sky and terrain data for the scene shader; unit 6: weather particles.
	private static final int MAX_TEXTURES = 7;
	// Keep sky/terrain samplers separate from Water's scene and transparency slots (4-12).
	private static final int FIRST_TEXTURE_UNIT = 16;
	// Small upload copies (weather atlases) are reused; a large one is released after use.
	private static final int RETAINED_STAGING_BYTES = 16 * 1024 * 1024;

	private final Client client;
	private final Map<Integer, Texture> textures = new HashMap<>();
	private final Map<Integer, Program> programs = new HashMap<>();
	private final Map<Integer, Buffer> buffers = new HashMap<>();
	private final Map<String, Integer> sceneUniforms = new HashMap<>();
	private int nextHandle = 1;
	private int activeProgram;
	private int sceneProgram;
	private Map<String, Integer> activeUniforms;
	private ByteBuffer textureStaging;
	private FloatBuffer floatTextureStaging;
	private boolean active = true;

	OpenGlContext(Client client)
	{
		this.client = client;
	}

	int createTexture(BufferedImage image, Wrap wrapS, Wrap wrapT)
	{
		return createTexture(image, wrapS, wrapT, Filter.MIPMAP_LINEAR, TextureOrigin.BOTTOM_LEFT);
	}

	int createTexture(BufferedImage image, Wrap wrapS, Wrap wrapT, Filter filter, TextureOrigin origin)
	{
		checkActive();
		int glWrapS = wrap(wrapS);
		int glWrapT = wrap(wrapT);
		Objects.requireNonNull(filter, "filter");
		Objects.requireNonNull(origin, "origin");
		int previousTexture = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		int texture = GL33C.glGenTextures();
		try
		{
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, glWrapS);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, glWrapT);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER,
				filter == Filter.LINEAR ? GL33C.GL_LINEAR : GL33C.GL_LINEAR_MIPMAP_LINEAR);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
			uploadTexture(texture, image, origin, filter);
			int handle = nextHandle++;
			textures.put(handle, new Texture(texture, origin, filter, image.getWidth()));
			return handle;
		}
		catch (RuntimeException | Error ex)
		{
			GL33C.glDeleteTextures(texture);
			throw ex;
		}
		finally
		{
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, previousTexture);
		}
	}

	void updateTexture(int texture, BufferedImage image)
	{
		checkActive();
		Texture target = textureResource(texture);
		uploadTexture(target.id, image, target.origin, target.filter);
		target.width = image.getWidth();
	}

	/** Width in pixels of an uploaded image texture (0 for unknown handles). */
	int textureWidth(int texture)
	{
		Texture target = textures.get(texture);
		return target == null ? 0 : target.width;
	}

	void destroyTexture(int texture)
	{
		checkActive();
		Texture target = textures.remove(texture);
		if (target != null)
		{
			GL33C.glDeleteTextures(target.id);
		}
	}

	int createFloatTexture(int width, int height, float[] rgba)
	{
		checkActive();
		int previousTexture = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		int texture = GL33C.glGenTextures();
		try
		{
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
			GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
			uploadFloatTexture(texture, width, height, rgba);
			int handle = nextHandle++;
			textures.put(handle, new Texture(texture, TextureOrigin.TOP_LEFT, Filter.LINEAR, width));
			return handle;
		}
		catch (RuntimeException | Error ex)
		{
			GL33C.glDeleteTextures(texture);
			throw ex;
		}
		finally
		{
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, previousTexture);
		}
	}

	void updateFloatTexture(int texture, int width, int height, float[] rgba)
	{
		checkActive();
		uploadFloatTexture(textureResource(texture).id, width, height, rgba);
	}

	int createProgram(String vertexShader, String fragmentShader)
	{
		checkActive();
		int handle = nextHandle++;
		programs.put(handle, new Program(compileProgram(vertexShader, fragmentShader)));
		return handle;
	}

	int createBuffer()
	{
		checkActive();
		int previousVao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
		int previousBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING);
		int vao = GL33C.glGenVertexArrays();
		int vbo = GL33C.glGenBuffers();
		try
		{
			GL33C.glBindVertexArray(vao);
			GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vbo);
			int stride = VERTEX_STRIDE_FLOATS * Float.BYTES;
			attribute(0, 3, stride, 0);
			attribute(1, 2, stride, 3L * Float.BYTES);
			attribute(2, 4, stride, 5L * Float.BYTES);
			attribute(3, 4, stride, 9L * Float.BYTES);
			int handle = nextHandle++;
			buffers.put(handle, new Buffer(vao, vbo));
			return handle;
		}
		catch (RuntimeException | Error ex)
		{
			GL33C.glDeleteBuffers(vbo);
			GL33C.glDeleteVertexArrays(vao);
			throw ex;
		}
		finally
		{
			GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, previousBuffer);
			GL33C.glBindVertexArray(previousVao);
		}
	}

	void upload(int buffer, float[] vertices, int vertexCount)
	{
		checkActive();
		Buffer target = buffer(buffer);
		Objects.requireNonNull(vertices, "vertices");
		int floatCount = Math.multiplyExact(vertexCount, VERTEX_STRIDE_FLOATS);
		if (vertexCount < 0 || floatCount > vertices.length)
		{
			throw new IllegalArgumentException("Vertex count exceeds the supplied data");
		}
		FloatBuffer staging = target.staging(floatCount);
		staging.clear();
		staging.put(vertices, 0, floatCount);
		staging.flip();
		int previousBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING);
		try
		{
			GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, target.vbo);
			if (target.gpuCapacityFloats < floatCount)
			{
				target.gpuCapacityFloats = staging.capacity();
				GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER,
					(long) target.gpuCapacityFloats * Float.BYTES, GL33C.GL_DYNAMIC_DRAW);
			}
			GL33C.glBufferSubData(GL33C.GL_ARRAY_BUFFER, 0, staging);
			target.vertexCount = vertexCount;
		}
		finally
		{
			GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, previousBuffer);
		}
	}

	void useProgram(int program)
	{
		checkActive();
		Program target = program(program);
		activeProgram = target.id;
		activeUniforms = target.uniforms;
		GL33C.glUseProgram(activeProgram);
	}

	void bindTexture(String sampler, int texture, int unit)
	{
		if (unit < 0 || unit >= MAX_TEXTURES)
		{
			throw new IllegalArgumentException("Texture unit must be between 0 and " + (MAX_TEXTURES - 1));
		}
		int glUnit = FIRST_TEXTURE_UNIT + unit;
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + glUnit);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture(texture));
		uniform1i(sampler, glUnit);
	}

	void uniform1i(String name, int value)
	{
		GL33C.glUniform1i(uniform(name), value);
	}

	void uniform1f(String name, float value)
	{
		GL33C.glUniform1f(uniform(name), value);
	}

	void uniform2f(String name, float x, float y)
	{
		GL33C.glUniform2f(uniform(name), x, y);
	}

	void uniform3f(String name, float x, float y, float z)
	{
		GL33C.glUniform3f(uniform(name), x, y, z);
	}

	void uniform4f(String name, float x, float y, float z, float w)
	{
		GL33C.glUniform4f(uniform(name), x, y, z, w);
	}

	void uniformMatrix4f(String name, float[] matrix)
	{
		if (matrix == null || matrix.length != 16)
		{
			throw new IllegalArgumentException("A 4x4 matrix must contain 16 floats");
		}
		GL33C.glUniformMatrix4fv(uniform(name), false, matrix);
	}

	void draw(int buffer, int firstVertex, int vertexCount, Depth depth, Blend blend)
	{
		checkActive();
		if (activeProgram == 0)
		{
			throw new IllegalStateException("No extension program is active");
		}
		Buffer target = buffer(buffer);
		if (firstVertex < 0 || vertexCount < 0 || firstVertex + vertexCount > target.vertexCount)
		{
			throw new IllegalArgumentException("Draw range is outside the vertex buffer");
		}

		GL33C.glDepthMask(false);
		GL33C.glDisable(GL33C.GL_CULL_FACE);
		GL33C.glEnable(GL33C.GL_BLEND);
		GL33C.glDisable(GL33C.GL_POLYGON_OFFSET_FILL);
		if (depth == Depth.NONE)
		{
			GL33C.glDisable(GL33C.GL_DEPTH_TEST);
		}
		else
		{
			GL33C.glEnable(GL33C.GL_DEPTH_TEST);
			GL33C.glDepthFunc(depth == Depth.SCENE_EQUAL ? GL33C.GL_GEQUAL : GL33C.GL_GREATER);
			if (depth == Depth.SCENE_EQUAL)
			{
				GL33C.glEnable(GL33C.GL_POLYGON_OFFSET_FILL);
				GL33C.glPolygonOffset(0f, 1f);
			}
		}
		if (blend == Blend.SCREEN)
		{
			// Colour arrives multiplied by its alpha: result = source + destination * (1 - source),
			// so bright areas gain less and nothing clips to white.
			GL33C.glBlendFuncSeparate(GL33C.GL_ONE, GL33C.GL_ONE_MINUS_SRC_COLOR, GL33C.GL_ONE, GL33C.GL_ONE);
		}
		else if (blend == Blend.ADDITIVE)
		{
			GL33C.glBlendFuncSeparate(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE, GL33C.GL_ONE, GL33C.GL_ONE);
		}
		else
		{
			GL33C.glBlendFuncSeparate(GL33C.GL_SRC_ALPHA, GL33C.GL_ONE_MINUS_SRC_ALPHA, GL33C.GL_ONE, GL33C.GL_ONE_MINUS_SRC_ALPHA);
		}
		GL33C.glBindVertexArray(target.vao);
		GL33C.glDrawArrays(GL33C.GL_TRIANGLES, firstVertex, vertexCount);
	}

	void useSceneProgram(int program)
	{
		checkActive();
		if (sceneProgram != program)
		{
			sceneUniforms.clear();
		}
		sceneProgram = program;
		activeProgram = program;
		activeUniforms = sceneUniforms;
		GL33C.glUseProgram(program);
	}

	void cacheSceneUniforms(int program, String... names)
	{
		checkActive();
		// The callback also runs after relinks that reuse the same GL program ID.
		sceneUniforms.clear();
		sceneProgram = program;
		for (String name : names)
		{
			sceneUniforms.put(name, GL33C.glGetUniformLocation(program, name));
		}
	}

	private void invalidateSceneUniforms()
	{
		sceneUniforms.clear();
		sceneProgram = 0;
	}

	void close()
	{
		if (!active)
		{
			return;
		}
		checkClientThread();
		textures.values().forEach(texture -> GL33C.glDeleteTextures(texture.id));
		programs.values().forEach(program -> GL33C.glDeleteProgram(program.id));
		buffers.values().forEach(buffer ->
		{
			GL33C.glDeleteBuffers(buffer.vbo);
			GL33C.glDeleteVertexArrays(buffer.vao);
		});
		textures.clear();
		programs.clear();
		buffers.clear();
		activeProgram = 0;
		invalidateSceneUniforms();
		activeUniforms = null;
		textureStaging = null;
		floatTextureStaging = null;
		active = false;
	}

	private int uniform(String name)
	{
		checkActive();
		if (activeProgram == 0 || activeUniforms == null)
		{
			throw new IllegalStateException("No shader program is active");
		}
		Objects.requireNonNull(name, "name");
		return activeUniforms.computeIfAbsent(name, key -> GL33C.glGetUniformLocation(activeProgram, key));
	}

	private int texture(int handle)
	{
		return textureResource(handle).id;
	}

	private Texture textureResource(int handle)
	{
		checkActive();
		Texture texture = textures.get(handle);
		if (texture == null)
		{
			throw new IllegalArgumentException("Unknown texture handle");
		}
		return texture;
	}

	private Program program(int handle)
	{
		Program program = programs.get(handle);
		if (program == null)
		{
			throw new IllegalArgumentException("Unknown program handle");
		}
		return program;
	}

	private Buffer buffer(int handle)
	{
		checkActive();
		Buffer buffer = buffers.get(handle);
		if (buffer == null)
		{
			throw new IllegalArgumentException("Unknown buffer handle");
		}
		return buffer;
	}

	private void checkActive()
	{
		checkClientThread();
		if (!active)
		{
			throw new IllegalStateException("GPU extension context is not active");
		}
	}

	private void checkClientThread()
	{
		if (!client.isClientThread())
		{
			throw new IllegalStateException("GPU extension calls must run on the client thread");
		}
	}

	private static void attribute(int index, int size, int stride, long offset)
	{
		GL33C.glVertexAttribPointer(index, size, GL33C.GL_FLOAT, false, stride, offset);
		GL33C.glEnableVertexAttribArray(index);
	}

	private static int wrap(Wrap wrap)
	{
		return Objects.requireNonNull(wrap, "wrap") == Wrap.REPEAT
			? GL33C.GL_REPEAT
			: GL33C.GL_CLAMP_TO_EDGE;
	}

	private void uploadTexture(int texture, BufferedImage image, TextureOrigin origin, Filter filter)
	{
		Objects.requireNonNull(image, "image");
		int width = image.getWidth();
		int height = image.getHeight();
		int maximum = GL33C.glGetInteger(GL33C.GL_MAX_TEXTURE_SIZE);
		long size = (long) width * height * 4;
		if (width <= 0 || height <= 0 || width > maximum || height > maximum || size > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException("Unsupported texture dimensions: " + width + "x" + height
				+ " (GPU maximum " + maximum + ")");
		}
		int previousTexture = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		try
		{
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
			// Decoded JPEG panoramas go straight from the image's own bytes; no per-pixel
			// conversion on the client thread and no extra full-size copy in Java memory.
			if (!uploadBgrImage(image, origin, width, height))
			{
				uploadConvertedImage(image, origin, width, height, (int) size);
			}
			if (GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_TEXTURE_WIDTH) != width
				|| GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_TEXTURE_HEIGHT) != height)
			{
				throw new IllegalStateException("GPU could not allocate texture " + width + "x" + height);
			}
			if (filter == Filter.MIPMAP_LINEAR)
			{
				GL33C.glGenerateMipmap(GL33C.GL_TEXTURE_2D);
			}
		}
		finally
		{
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, previousTexture);
		}
	}

	/** The existing per-pixel RGBA copy, for image types without a direct upload path. */
	private void uploadConvertedImage(BufferedImage image, TextureOrigin origin, int width, int height, int size)
	{
		int[] argb = new int[width];
		ByteBuffer pixels = textureStaging(size);
		pixels.clear();
		for (int row = 0; row < height; ++row)
		{
			int y = origin == TextureOrigin.BOTTOM_LEFT ? height - 1 - row : row;
			image.getRGB(0, y, width, 1, argb, 0, width);
			for (int x = 0; x < width; ++x)
			{
				int pixel = argb[x];
				pixels.put((byte) (pixel >> 16));
				pixels.put((byte) (pixel >> 8));
				pixels.put((byte) pixel);
				pixels.put((byte) (pixel >> 24));
			}
		}
		pixels.flip();
		GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
			GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
		if (textureStaging != null && textureStaging.capacity() > RETAINED_STAGING_BYTES)
		{
			// Do not keep a panorama-sized direct buffer for the rest of the session.
			textureStaging = null;
		}
	}

	/**
	 * Uploads a TYPE_3BYTE_BGR image (what ImageIO decodes JPEGs to) through a temporary
	 * pixel buffer object, copying whole rows from the image's own byte array. Returns
	 * false, without changing the texture, when the image has another layout.
	 */
	private static boolean uploadBgrImage(BufferedImage image, TextureOrigin origin, int width, int height)
	{
		byte[] data = bgrBytes(image, width, height);
		if (data == null)
		{
			return false;
		}
		int rowBytes = width * 3;
		long size = (long) rowBytes * height;
		int previousUnpackBuffer = GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
		int previousAlignment = GL33C.glGetInteger(GL33C.GL_UNPACK_ALIGNMENT);
		int previousRowLength = GL33C.glGetInteger(GL33C.GL_UNPACK_ROW_LENGTH);
		int previousSkipRows = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_ROWS);
		int previousSkipPixels = GL33C.glGetInteger(GL33C.GL_UNPACK_SKIP_PIXELS);
		int pbo = GL33C.glGenBuffers();
		try
		{
			GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, pbo);
			GL33C.glBufferData(GL33C.GL_PIXEL_UNPACK_BUFFER, size, GL33C.GL_STREAM_DRAW);
			ByteBuffer mapped = GL33C.glMapBufferRange(GL33C.GL_PIXEL_UNPACK_BUFFER, 0, size,
				GL33C.GL_MAP_WRITE_BIT | GL33C.GL_MAP_INVALIDATE_BUFFER_BIT);
			if (mapped == null)
			{
				return false;
			}
			for (int row = 0; row < height; ++row)
			{
				int y = origin == TextureOrigin.BOTTOM_LEFT ? height - 1 - row : row;
				mapped.put(data, y * rowBytes, rowBytes);
			}
			if (!GL33C.glUnmapBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER))
			{
				return false; // Contents were lost; use the copy path instead.
			}
			GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
			GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, 0);
			GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, 0);
			GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, 0);
			GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGB8, width, height, 0,
				GL33C.GL_BGR, GL33C.GL_UNSIGNED_BYTE, 0L);
			return true;
		}
		finally
		{
			GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER, previousUnpackBuffer);
			GL33C.glDeleteBuffers(pbo);
			GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, previousAlignment);
			GL33C.glPixelStorei(GL33C.GL_UNPACK_ROW_LENGTH, previousRowLength);
			GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_ROWS, previousSkipRows);
			GL33C.glPixelStorei(GL33C.GL_UNPACK_SKIP_PIXELS, previousSkipPixels);
		}
	}

	/** The packed B,G,R bytes of a plain TYPE_3BYTE_BGR image, or null for any other layout. */
	static byte[] bgrBytes(BufferedImage image, int width, int height)
	{
		if (image.getType() != BufferedImage.TYPE_3BYTE_BGR)
		{
			return null;
		}
		Raster raster = image.getRaster();
		if (!(raster.getDataBuffer() instanceof DataBufferByte)
			|| !(raster.getSampleModel() instanceof PixelInterleavedSampleModel))
		{
			return null;
		}
		DataBufferByte buffer = (DataBufferByte) raster.getDataBuffer();
		PixelInterleavedSampleModel model = (PixelInterleavedSampleModel) raster.getSampleModel();
		int[] offsets = model.getBandOffsets();
		boolean packed = buffer.getNumBanks() == 1 && buffer.getOffset() == 0
			&& raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0
			&& raster.getWidth() == width && raster.getHeight() == height
			&& model.getPixelStride() == 3 && model.getScanlineStride() == width * 3
			&& offsets.length == 3 && offsets[0] == 2 && offsets[1] == 1 && offsets[2] == 0;
		byte[] data = buffer.getData();
		return packed && data.length >= (long) width * height * 3 ? data : null;
	}

	private void uploadFloatTexture(int texture, int width, int height, float[] rgba)
	{
		Objects.requireNonNull(rgba, "rgba");
		int floatCount = Math.multiplyExact(Math.multiplyExact(width, height), 4);
		if (width <= 0 || height <= 0 || rgba.length != floatCount)
		{
			throw new IllegalArgumentException("Invalid float texture dimensions");
		}
		FloatBuffer pixels = floatTextureStaging(floatCount);
		pixels.clear();
		pixels.put(rgba).flip();
		int previousTexture = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
		try
		{
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
			GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA32F, width, height, 0,
				GL33C.GL_RGBA, GL33C.GL_FLOAT, pixels);
		}
		finally
		{
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, previousTexture);
		}
	}

	private ByteBuffer textureStaging(int requiredBytes)
	{
		if (textureStaging == null || textureStaging.capacity() < requiredBytes)
		{
			textureStaging = ByteBuffer.allocateDirect(requiredBytes);
		}
		return textureStaging;
	}

	private FloatBuffer floatTextureStaging(int requiredFloats)
	{
		if (floatTextureStaging == null || floatTextureStaging.capacity() < requiredFloats)
		{
			floatTextureStaging = ByteBuffer.allocateDirect(Math.multiplyExact(requiredFloats, Float.BYTES))
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		}
		return floatTextureStaging;
	}

	private static int compileProgram(String vertexSource, String fragmentSource)
	{
		int vertex = compileShader(GL33C.GL_VERTEX_SHADER, Objects.requireNonNull(vertexSource));
		int fragment = 0;
		int program = 0;
		try
		{
			fragment = compileShader(GL33C.GL_FRAGMENT_SHADER, Objects.requireNonNull(fragmentSource));
			program = GL33C.glCreateProgram();
			GL33C.glAttachShader(program, vertex);
			GL33C.glAttachShader(program, fragment);
			GL33C.glLinkProgram(program);
			if (GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) == GL33C.GL_FALSE)
			{
				throw new IllegalArgumentException("GPU extension program failed to link: " + GL33C.glGetProgramInfoLog(program));
			}
			return program;
		}
		catch (RuntimeException ex)
		{
			if (program != 0)
			{
				GL33C.glDeleteProgram(program);
				program = 0;
			}
			throw ex;
		}
		finally
		{
			if (program != 0)
			{
				GL33C.glDetachShader(program, vertex);
				GL33C.glDetachShader(program, fragment);
			}
			GL33C.glDeleteShader(vertex);
			if (fragment != 0)
			{
				GL33C.glDeleteShader(fragment);
			}
		}
	}

	private static int compileShader(int type, String source)
	{
		int shader = GL33C.glCreateShader(type);
		GL33C.glShaderSource(shader, source);
		GL33C.glCompileShader(shader);
		if (GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS) != GL33C.GL_TRUE)
		{
			String error = GL33C.glGetShaderInfoLog(shader);
			GL33C.glDeleteShader(shader);
			throw new IllegalArgumentException("GPU extension shader failed to compile: " + error);
		}
		return shader;
	}

	// Texture units 16..21 carry our scene uniforms through the subsequent world draw.
	// Restore the other state changed by sky/weather drawing to its incoming values.
	static final class DrawState
	{
		private final int program = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
		private final int vao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
		private final int arrayBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING);
		private final int activeTexture = GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
		private final boolean depthMask = GL33C.glGetBoolean(GL33C.GL_DEPTH_WRITEMASK);
		private final boolean depthTest = GL33C.glIsEnabled(GL33C.GL_DEPTH_TEST);
		private final int depthFunc = GL33C.glGetInteger(GL33C.GL_DEPTH_FUNC);
		private final boolean cull = GL33C.glIsEnabled(GL33C.GL_CULL_FACE);
		private final boolean blend = GL33C.glIsEnabled(GL33C.GL_BLEND);
		private final int blendSrcRgb = GL33C.glGetInteger(GL33C.GL_BLEND_SRC_RGB);
		private final int blendDstRgb = GL33C.glGetInteger(GL33C.GL_BLEND_DST_RGB);
		private final int blendSrcAlpha = GL33C.glGetInteger(GL33C.GL_BLEND_SRC_ALPHA);
		private final int blendDstAlpha = GL33C.glGetInteger(GL33C.GL_BLEND_DST_ALPHA);
		private final boolean polygonOffset = GL33C.glIsEnabled(GL33C.GL_POLYGON_OFFSET_FILL);
		private final float polygonFactor = GL33C.glGetFloat(GL33C.GL_POLYGON_OFFSET_FACTOR);
		private final float polygonUnits = GL33C.glGetFloat(GL33C.GL_POLYGON_OFFSET_UNITS);

		void restore()
		{
			GL33C.glUseProgram(program);
			GL33C.glBindVertexArray(vao);
			GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, arrayBuffer);
			GL33C.glActiveTexture(activeTexture);
			GL33C.glDepthMask(depthMask);
			GL33C.glDepthFunc(depthFunc);
			GL33C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
			GL33C.glPolygonOffset(polygonFactor, polygonUnits);
			restoreEnabled(GL33C.GL_DEPTH_TEST, depthTest);
			restoreEnabled(GL33C.GL_CULL_FACE, cull);
			restoreEnabled(GL33C.GL_BLEND, blend);
			restoreEnabled(GL33C.GL_POLYGON_OFFSET_FILL, polygonOffset);
		}

		private static void restoreEnabled(int capability, boolean enabled)
		{
			if (enabled)
			{
				GL33C.glEnable(capability);
			}
			else
			{
				GL33C.glDisable(capability);
			}
		}
	}

	private static final class Program
	{
		private final int id;
		private final Map<String, Integer> uniforms = new HashMap<>();

		private Program(int id)
		{
			this.id = id;
		}
	}

	private static final class Texture
	{
		private final int id;
		private final TextureOrigin origin;
		private final Filter filter;
		private int width;

		private Texture(int id, TextureOrigin origin, Filter filter, int width)
		{
			this.id = id;
			this.origin = origin;
			this.filter = filter;
			this.width = width;
		}
	}

	private static final class Buffer
	{
		private final int vao;
		private final int vbo;
		private FloatBuffer staging;
		private int gpuCapacityFloats;
		private int vertexCount;

		private Buffer(int vao, int vbo)
		{
			this.vao = vao;
			this.vbo = vbo;
		}

		private FloatBuffer staging(int requiredFloats)
		{
			if (staging == null || staging.capacity() < requiredFloats)
			{
				int capacity = Math.max(64, Integer.highestOneBit(Math.max(1, requiredFloats - 1)) << 1);
				staging = ByteBuffer.allocateDirect(Math.multiplyExact(capacity, Float.BYTES))
					.order(ByteOrder.nativeOrder())
					.asFloatBuffer();
			}
			return staging;
		}
	}

	enum Wrap
	{
		CLAMP,
		REPEAT
	}

	enum Filter
	{
		LINEAR,
		MIPMAP_LINEAR
	}

	enum TextureOrigin
	{
		TOP_LEFT,
		BOTTOM_LEFT
	}

	enum Depth
	{
		NONE,
		SCENE,
		SCENE_EQUAL
	}

	enum Blend
	{
		ALPHA,
		ADDITIVE,
		SCREEN
	}

}
