class SheetImageDecoder {
BufferedImage readAssetImage(File sourceFile) throws IOException {
		if (sourceFile.isFile()) {
			return ImageIO.read(sourceFile);
		}
		String resource = getEmbeddedAssetResource(sourceFile);
		if (resource == null) {
			return null;
		}
		try (InputStream input = getResourceClassLoader().getResourceAsStream(resource)) {
			return input == null ? null : ImageIO.read(input);
		}
	}
Entry loadExternalNpcDirectionSheet(File sourceFile, String spriteName,
			int[] directionColumnWidths, int framesPerDirection) {
		return loadExternalNpcDirectionSheet(sourceFile, spriteName, directionColumnWidths,
			framesPerDirection, -1);
	}
Entry loadExternalNpcDirectionSheet(File sourceFile, String spriteName,
			int[] directionColumnWidths, int framesPerDirection, int transparentGuideRgb) {
		if (spriteName == null || spriteName.length() == 0 || directionColumnWidths == null
			|| directionColumnWidths.length == 0 || framesPerDirection <= 0) {
			return null;
		}
		try {
			BufferedImage source = readAssetImage(sourceFile);
			if (source == null || source.getHeight() % framesPerDirection != 0) {
				return null;
			}
			int totalWidth = 0;
			for (int width : directionColumnWidths) {
				if (width <= 0) {
					return null;
				}
				totalWidth += width;
			}
			if (totalWidth != source.getWidth()) {
				return null;
			}
			int frameHeight = source.getHeight() / framesPerDirection;
			Entry entry = new Entry(spriteName, Entry.TYPE.NPC, null,
				directionColumnWidths.length * framesPerDirection);
			int sourceX = 0;
			for (int direction = 0; direction < directionColumnWidths.length; direction++) {
				int frameWidth = directionColumnWidths[direction];
				for (int frameIndex = 0; frameIndex < framesPerDirection; frameIndex++) {
					BufferedImage sourceFrame = source.getSubimage(
						sourceX, frameIndex * frameHeight, frameWidth, frameHeight);
					Frame frame = new Frame(frameWidth, frameHeight, false, 0, 0,
						frameWidth, frameHeight);
					sourceFrame.getRGB(0, 0, frameWidth, frameHeight, frame.getPixels(), 0, frameWidth);
					if (transparentGuideRgb >= 0) {
						clearExactRgb(frame.getPixels(), transparentGuideRgb);
					}
					normalizePixels(frame.getPixels(), 64);
					entry.getFrames()[direction * framesPerDirection + frameIndex] = frame;
				}
				sourceX += frameWidth;
			}
			return entry;
		} catch (IOException failure) {
			System.out.println("Failed to load variable-width external NPC direction sheet "
				+ sourceFile.getPath() + ": " + failure.getMessage());
			return null;
		}
	}
int getExternalSpritePixel(int argb, int alphaThreshold) {
		int alpha = argb >>> 24;
		if (alpha < alphaThreshold) {
			return 0;
		}
		int rgb = argb & 0xFFFFFF;
		return rgb == 0 ? 0x010101 : rgb;
	}
private void normalizePixels(int[] pixels, int alphaThreshold) {
		for (int i = 0; i < pixels.length; i++) {
			pixels[i] = getExternalSpritePixel(pixels[i], alphaThreshold);
		}
	}
}
