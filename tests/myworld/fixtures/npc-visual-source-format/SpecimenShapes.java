enum SpecimenShapes {
	CREATURE(1173, "Test creature", "speckled", 32,32,32,32,32,32),
	REUSE(1174, "Other creature", "curved", 24,24,24,24,24);
	final int npcId; final String displayName, assetName; final int[] columns;
	SpecimenShapes(int npcId,String displayName,String assetName,int...columns){
		this.npcId=npcId;this.displayName=displayName;this.assetName=assetName;this.columns=columns;
	}
	String animationName(){return "demo-"+assetName;}
	boolean combatEnabled(){return true;}
	int[] columnWidths(){return columns.clone();}
	int loadedFrameCount(){return 18;}
	int frameHeight(){return this==CREATURE?32:40;}
	int cameraWidth(){return columns[0]*12/5;}
	int cameraHeight(){return frameHeight()*12/5;}
	Entry withCombatFrames(Entry source){
		if(this!=REUSE||source==null||source.getFrames().length!=15)return source;
		Entry result=new Entry(source.getID(),source.getType(),source.getLayer(),18);
		for(int i=0;i<18;i++)result.getFrames()[i]=source.getFrames()[i<15?i:i-9].clone();
		return result;
	}
}
