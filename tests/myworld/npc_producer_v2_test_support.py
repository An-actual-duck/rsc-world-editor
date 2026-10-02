"""Synthetic maintained producer data; never invokes target code or a target build."""
import hashlib
import json
import struct
import zipfile
from pathlib import Path
from adaptive_project_test_support import declare_effective_content_sources

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write_json(path,value):
 path=Path(path);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')

def install_v2_fixture(target,npc_ids=(0,),frame_count=15,with_probes=True):
 """Caller owns disposable target. Existing NPC/server identities are untouched."""
 target=Path(target);config=target/'server/myworld.conf'
 # Explicit renderer flags avoid relying on synthetic source defaults.
 text=config.read_text()
 for key in ['custom_sprites','allow_bearded_ladies']:
  if key+':' not in text:text+='\n'+key+': false\n'
 config.write_text(text)
 definitions='server/conf/server/defs/'
 declare_effective_content_sources(target,item_sources=())
 source_rows=[]
 def source(identity,role,path,payload=None):
  file=target/path
  if payload is not None:file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(payload)
  source_rows.append({'sourceId':identity,'role':role,'relativePath':path,'sha256':sha(file)});return identity
 source('base','effective-npc-definition',definitions+'NpcDefs.json')
 source('custom','effective-npc-definition',definitions+'NpcDefsCustom.json')
 if 'based_config_data: 18' in text:source('patch','effective-npc-definition',definitions+'NpcDefsPatch18.json')
 if 'want_myworld: true' in text:source('world','effective-npc-definition',definitions+'NpcDefsMyWorld.json')
 for identity,path in [('server-loader','server/src/com/openrsc/server/external/EntityHandler.java'),('client-loader','Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java'),('initializer','Client_Base/src/orsc/mudclient.java'),('resolver','Client_Base/src/orsc/graphics/two/GraphicsController.java')]:
  role={'server-loader':'server-npc-loader','client-loader':'client-npc-loader','initializer':'client-sprite-initializer','resolver':'client-frame-resolver'}[identity]
  source(identity,role,path,b'// Synthetic inert captured source; never executed.\n')
 source('helper','producer-helper-source','tools/item-visual-provider/FixtureExporter.java',b'// Synthetic inert exporter source.\n')
 package=target/'world-builder-provider';package.mkdir(exist_ok=True)
 frames=[struct.pack('>iiBiiiiI',1,1,1,-1,2,3,4,0x102000+i) for i in range(frame_count)]
 archive=package/'npc-frames.zip'
 with zipfile.ZipFile(archive,'w') as zipped:
  for i,payload in enumerate(frames):zipped.writestr(f'frames/{i}.dat',payload)
 # Frame artifacts are target-owned inert evidence, also adjacent package input.
 target_archive=target/'server/conf/world-builder/npc-frames.zip';target_archive.parent.mkdir(parents=True,exist_ok=True);target_archive.write_bytes(archive.read_bytes())
 source('frames','resolved-frame-artifact','server/conf/world-builder/npc-frames.zip')
 probes=[{'probeId':'pack-config','kind':'file','relativePath':'Client_Base/Cache/config.txt','present':False}]
 if with_probes:probes.append({'probeId':'optional-image','kind':'file','relativePath':'Client_Base/dev/myworld/assets/npcs/optional.png','present':False})
 source_a=frame_count>15;selected=18 if source_a else 15
 animation={'animationId':0,'name':'fixture','category':'npc','charColour':1,'blueMask':0,'genderModel':0,'hasCombatFrames':source_a,'hasSpecialCombatFrames':False,'resolvedFrameCount':frame_count,'npcMaskPolicy':'hair-and-skin',
  'authoringPreview':{'behavior':'generic-layered-preview-v1','hasCombatFrames':source_a,'hasSpecialCombatFrames':False,'frameIndices':list(range(selected)),'limitations':['source-secondary-attack-not-previewed'] if frame_count==21 else[]},
  'resolution':{'resolverSourceId':'resolver','resolverSourceSha256':next(row['sha256'] for row in source_rows if row['sourceId']=='resolver'),'inputSourceIds':['resolver','frames'],'precedenceSourceIds':['resolver','frames'],'probeIds':[row['probeId'] for row in probes]},
  'frames':{'kind':'resolved-rgb','assetId':'frames','frameKeys':[f'frames/{i}.dat' for i in range(frame_count)],'frameSha256s':[hashlib.sha256(frame).hexdigest() for frame in frames]}}
 document={'schemaVersion':2,'manifestType':'world-builder-npc-definitions',
  'provider':{'identity':'synthetic-v2','rendererProfile':'openrsc-effective-npc-preview-v1','capturePhase':'after-selected-client-sprite-initialization-v1','spriteBranch':'authentic','configuration':{'relativePath':'server/myworld.conf','sha256':sha(config)},'clientFlags':{'Config.S_WANT_CUSTOM_SPRITES':False,'Config.S_ALLOW_BEARDED_LADIES':False},'sources':source_rows,'resolutionProbes':probes},
  'selection':{'kind':'complete-effective-server-npc-catalog','npcIds':list(npc_ids)},
  'assetProviders':[{'assetId':'frames','sourceId':'frames','format':'world-builder-rgb-frame-zip-v1','targetRelativePath':'server/conf/world-builder/npc-frames.zip','packageRelativePath':'npc-frames.zip','sha256':sha(archive)}],
  'npcDefinitions':[{'npcId':identity,'spriteAnimationIds':[0]+[-1]*11,'hairColour':0x123456,'topColour':0,'bottomColour':0,'skinColour':0x887766,'cameraWidth':145,'cameraHeight':200,'walkModel':4,'combatModel':4,'combatSprite':1} for identity in npc_ids],
  'animationDefinitions':[animation]}
 manifest=package/'npc-definitions-v2.json';write_json(manifest,document);return manifest,document
