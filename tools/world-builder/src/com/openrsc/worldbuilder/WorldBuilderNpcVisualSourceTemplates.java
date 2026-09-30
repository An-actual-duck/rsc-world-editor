package com.openrsc.worldbuilder;

/** Structural templates for the supported loader API, with symbolic identifiers. */
final class WorldBuilderNpcVisualSourceTemplates {
	static final String CALLER =
		"" +
		"\t\tfor ($table $preview" +
		"\t\t\t\t: $table.values()) {" +
		"\t\t\tFile $source = this.$externalAssetLoader.findFirstFile(new String[] {" +
		"\t\t\t\t$root" +
		"\t\t\t}, $preview.$assetName + \".png\");" +
		"\t\t\tEntry $entry =" +
		"\t\t\t\tthis.$externalAssetLoader.loadExternalNpcDirectionSheet($source," +
		"\t\t\t\t\t$preview.$animationName(), $preview.$columnWidths(), $beats);" +
		"\t\t\tif ($entry == null) {" +
		"\t\t\t\tSystem.out.println($log + $preview.$assetName);" +
		"\t\t\t\tcontinue;" +
		"\t\t\t}" +
		"\t\t\t$entry = $preview.$withCombatFrames($entry);" +
		"\t\t\t$EntityHandler.$activate($preview);" +
		"\t\t\tif (S_WANT_CUSTOM_SPRITES) {" +
		"\t\t\t\tMap<String, Entry> $sprites = getSurface().spriteTree.get(\"npc\");" +
		"\t\t\t\tif ($sprites != null) $sprites.put($preview.$animationName(), $entry);" +
		"\t\t\t} else {" +
		"\t\t\t\tAnimationDef $animation = $EntityHandler.getAnimationDef($EntityHandler.$animationGetter($preview));" +
		"\t\t\t\tint $frameCount = $preview.$loadedFrameCount();" +
		"\t\t\t\tfor (int $frame = 0; $frame < $frameCount; $frame++) {" +
		"\t\t\t\t\tgetSurface().$sprites[$animation.getNumber() + $frame] = $entry.getFrames()[$frame].getSprite();" +
		"\t\t\t\t}" +
		"\t\t\t}" +
		"\t\t}" +
		"\t";
	static final String LOADER =
		"" +
		"\t\tif ($spriteName == null || $spriteName.length() == 0 || $directionColumnWidths == null" +
		"\t\t\t|| $directionColumnWidths.length == 0 || $framesPerDirection <= 0) {" +
		"\t\t\treturn null;" +
		"\t\t}" +
		"\t\ttry {" +
		"\t\t\tBufferedImage $source = readAssetImage($sourceFile);" +
		"\t\t\tif ($source == null || $source.getHeight() % $framesPerDirection != 0) {" +
		"\t\t\t\treturn null;" +
		"\t\t\t}" +
		"\t\t\tint $totalWidth = 0;" +
		"\t\t\tfor (int $width : $directionColumnWidths) {" +
		"\t\t\t\tif ($width <= 0) {" +
		"\t\t\t\t\treturn null;" +
		"\t\t\t\t}" +
		"\t\t\t\t$totalWidth += $width;" +
		"\t\t\t}" +
		"\t\t\tif ($totalWidth != $source.getWidth()) {" +
		"\t\t\t\treturn null;" +
		"\t\t\t}" +
		"\t\t\tint $frameHeight = $source.getHeight() / $framesPerDirection;" +
		"\t\t\tEntry $entry = new Entry($spriteName, Entry.TYPE.NPC, null," +
		"\t\t\t\t$directionColumnWidths.length * $framesPerDirection);" +
		"\t\t\tint $sourceX = 0;" +
		"\t\t\tfor (int $direction = 0; $direction < $directionColumnWidths.length; $direction++) {" +
		"\t\t\t\tint $frameWidth = $directionColumnWidths[$direction];" +
		"\t\t\t\tfor (int $frameIndex = 0; $frameIndex < $framesPerDirection; $frameIndex++) {" +
		"\t\t\t\t\tBufferedImage $sourceFrame = $source.getSubimage(" +
		"\t\t\t\t\t\t$sourceX, $frameIndex * $frameHeight, $frameWidth, $frameHeight);" +
		"\t\t\t\t\tFrame $frame = new Frame($frameWidth, $frameHeight, false, 0, 0," +
		"\t\t\t\t\t\t$frameWidth, $frameHeight);" +
		"\t\t\t\t\t$sourceFrame.getRGB(0, 0, $frameWidth, $frameHeight, $frame.getPixels(), 0, $frameWidth);" +
		"\t\t\t\t\tif ($transparentGuideRgb >= 0) {" +
		"\t\t\t\t\t\tclearExactRgb($frame.getPixels(), $transparentGuideRgb);" +
		"\t\t\t\t\t}" +
		"\t\t\t\t\tnormalizePixels($frame.getPixels(), $alpha);" +
		"\t\t\t\t\t$entry.getFrames()[$direction * $framesPerDirection + $frameIndex] = $frame;" +
		"\t\t\t\t}" +
		"\t\t\t\t$sourceX += $frameWidth;" +
		"\t\t\t}" +
		"\t\t\treturn $entry;" +
		"\t\t} catch (IOException $failure) {" +
		"\t\t\tSystem.out.println($log" +
		"\t\t\t\t+ $sourceFile.getPath() + \": \" + $failure.getMessage());" +
		"\t\t\treturn null;" +
		"\t\t}" +
		"\t";
	static final String NORMALIZE =
		"" +
		"\t\tfor (int $i = 0; $i < $pixels.length; $i++) {" +
		"\t\t\t$pixels[$i] = getExternalSpritePixel($pixels[$i], $alphaThreshold);" +
		"\t\t}" +
		"\t";
	static final String PIXEL =
		"" +
		"\t\tint $alpha = $argb >>> 24;" +
		"\t\tif ($alpha < $alphaThreshold) {" +
		"\t\t\treturn 0;" +
		"\t\t}" +
		"\t\tint $rgb = $argb & 0xFFFFFF;" +
		"\t\treturn $rgb == 0 ? 0x010101 : $rgb;" +
		"\t";
	static final String COMBAT =
		"" +
		"\t\tif (this != $reuseConstant || $source == null || $source.getFrames().length != $sourceCount) return $source;" +
		"\t\tEntry $result = new Entry(" +
		"\t\t\t$source.getID(), $source.getType(), $source.getLayer(), $targetCount);" +
		"\t\tfor (int $i = 0; $i < $targetCount; $i++) $result.getFrames()[$i] = $source.getFrames()[$i < $sourceCount ? $i : $i - $reuseOffset].clone();" +
		"\t\treturn $result;" +
		"\t";
}
