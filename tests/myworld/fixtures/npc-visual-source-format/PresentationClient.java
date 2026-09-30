class PresentationClient {
private final SheetImageDecoder externalAssetLoader = new SheetImageDecoder();
private void installCreatureArt() {
		for (SpecimenShapes preview
				: SpecimenShapes.values()) {
			File source = this.externalAssetLoader.findFirstFile(new String[] {
				"art/paint.v4/npc.samples"
			}, preview.assetName + ".png");
			orsc.graphics.two.SpriteArchive.Entry entry =
				this.externalAssetLoader.loadExternalNpcDirectionSheet(source,
					preview.animationName(), preview.columnWidths(), 3);
			if (entry == null) {
				System.out.println("Missing or invalid movement preview: " + preview.assetName);
				continue;
			}
			entry = preview.withCombatFrames(entry);
			CreatureDefinitions.activateVisual(preview);
			if (S_WANT_CUSTOM_SPRITES) {
				Map<String, orsc.graphics.two.SpriteArchive.Entry> sprites = getSurface().spriteTree.get("npc");
				if (sprites != null) sprites.put(preview.animationName(), entry);
			} else {
				AnimationDef animation = CreatureDefinitions.getAnimationDef(CreatureDefinitions.getVisualAnimation(preview));
				int frameCount = preview.loadedFrameCount();
				for (int frame = 0; frame < frameCount; frame++) {
					getSurface().sprites[animation.getNumber() + frame] = entry.getFrames()[frame].getSprite();
				}
			}
		}
	}
}
