class CreatureDefinitions {
	static int getVisualAnimation(SpecimenShapes preview){
		Integer id=registered.get(preview);
		if(id==null)throw new IllegalStateException("Animation not registered: "+preview);
		return id;
	}
	static void activateVisual(SpecimenShapes preview){
		NPCDef npc=getNpcDef(preview.npcId);
		if(npc==null||!preview.displayName.equals(npc.getName()))return;
		npc.sprites[0]=getVisualAnimation(preview);
	}
	static void loadAnimations(){
		for(SpecimenShapes preview:SpecimenShapes.values()){
			registered.put(preview,animations.size());
			animations.add(new AnimationDef(preview.animationName(),"npc",0,0,preview.combatEnabled(),false,0));
		}
	}
	static void loadDefinitions(){
		for(SpecimenShapes preview:SpecimenShapes.values()){
			setCustomNpcDefinition(preview.npcId,new NPCDef(preview.displayName,"test","",1,1,1,1,true,
				new int[]{0,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1},0,0,0,0,
				preview.cameraWidth(),preview.cameraHeight(),10,7,5,preview.npcId));
		}
	}
}
