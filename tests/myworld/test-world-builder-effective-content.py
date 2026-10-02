#!/usr/bin/env python3
"""Content identity is independent of map population and source-file expansion."""
import importlib.util
import json
import gzip
import hashlib
import struct
import zipfile
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
CLASSES = ROOT / 'output/world-builder-tools/classes'
FIXTURE = ROOT / 'tests/fixtures/project-content-bundle-v1/bundle'
spec = importlib.util.spec_from_file_location('content_generator', ROOT / 'scripts/generate-project-content-bundle-v1-fixture.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)
HARNESS = '''package com.openrsc.worldbuilder;
import java.nio.file.Paths; import java.util.*;
public class EffectiveContentHarness {
 public static void main(String[] args) throws Exception {
  WorldBuilderEffectiveContent.Index value=WorldBuilderEffectiveContent.index(WorldBuilderProjectContentBundle.read(Paths.get(args[0])));
  Map<String,Object> out=new TreeMap<>(); out.put("fingerprint",value.contentSha256);
  for (Map.Entry<String,Map<Integer,WorldBuilderEffectiveContent.Entry>> family:value.families.entrySet()) {
   Map<String,Object> rows=new TreeMap<>();
   for(WorldBuilderEffectiveContent.Entry e:family.getValue().values()) rows.put(Integer.toString(e.id),Arrays.asList(e.name,e.semanticSha256,e.visualSha256,e.provenance));
   out.put(family.getKey(),rows);
  }
  if(args.length>1){
   WorldBuilderEffectiveContent.writeVisualReport(Paths.get(args[1]),WorldBuilderEffectiveContent.visualReport(WorldBuilderProjectContentBundle.read(Paths.get(args[0]))));
   out.put("warnings",value.visualWarnings());out.put("summary",WorldBuilderEffectiveContent.projectWarningSummary(Paths.get(args[1])));
  }
  System.out.println(WorldBuilderJsonDocuments.pretty(out));
 }
}'''

class EffectiveContentTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  subprocess.run([str(ROOT/'scripts/build-tools.sh')],check=True)
  cls.tmp=tempfile.TemporaryDirectory(prefix='effective-content-harness-')
  p=Path(cls.tmp.name); (p/'EffectiveContentHarness.java').write_text(HARNESS)
  subprocess.run(['javac','-cp',str(CLASSES),'-d',str(p),str(p/'EffectiveContentHarness.java')],check=True)
 @classmethod
 def tearDownClass(cls): cls.tmp.cleanup()
 def run_index(self, root, success=True, report=None):
  result=subprocess.run(['java','-cp',os.pathsep.join([self.tmp.name,str(CLASSES)]),'com.openrsc.worldbuilder.EffectiveContentHarness',str(root),*([str(report)] if report else [])],text=True,capture_output=True)
  self.assertEqual(success,result.returncode==0,result.stdout+result.stderr)
  return json.loads(result.stdout) if success else result.stderr
 def seal(self,root,catalog=None):
  old=generator.catalog
  try:
   if catalog is not None: generator.catalog=lambda: catalog
   content={p.relative_to(root/'files').as_posix():p.read_bytes() for p in (root/'files').rglob('*') if p.is_file()}
   manifest=generator.build_manifest(content)
   (root/'manifest.json').write_bytes(generator.pretty(manifest))
  finally: generator.catalog=old
 def test_full_unplaced_catalog_and_vanilla_overrides(self):
  result=self.run_index(FIXTURE)
  self.assertEqual({'floor','boundary','scenery','npc','ground-item','fingerprint'},set(result))
  self.assertEqual('fixture-world-npc',result['npc']['846'][0])
  self.assertEqual('fixture-patch-npc',result['npc']['100'][0])
  self.assertEqual(847,len(result['npc']))
  self.assertEqual(4,len(result['ground-item']))
  self.assertEqual(32,len(result['floor']))
  self.assertEqual(220,len(result['boundary']))
 def test_additive_and_changed_definitions_are_per_identity_in_every_family(self):
  before=self.run_index(FIXTURE)
  for family,filename,element in [('floor','TileDef.xml','TileDef'),('boundary','DoorDef.xml','DoorDef'),('scenery','GameObjectDef.xml','GameObjectDef'),('npc','NpcDefsCustom.json',None),('ground-item','ItemDefsCustom.json',None)]:
   with self.subTest(family=family),tempfile.TemporaryDirectory(prefix='effective-content-') as tmp:
    root=Path(tmp)/'bundle';shutil.copytree(FIXTURE,root)
    path=root/'files/server/conf/server/defs'/filename
    if element:
     text=path.read_text();text=text.replace(f'</{element}-array>',f'<{element}><name>New available content</name></{element}></{element}-array>');path.write_text(text)
     newid=len(before[family])
    else:
     document=json.loads(path.read_text());rows=next(iter(document.values()));newid=847 if family=='npc' else 9003
     rows.append({'id':newid,'name':'New available content'});path.write_text(json.dumps(document))
    catalog=generator.catalog();field={'floor':'tiles','boundary':'boundaries','npc':'npcs','ground-item':'groundItems','scenery':'scenery'}[family]
    catalog[field].append(newid);catalog['catalogSha256']=generator.self_hash(catalog,'catalogSha256');self.seal(root,catalog)
    after=self.run_index(root)
    self.assertEqual('New available content',after[family][str(newid)][0])
    for oldid,row in before[family].items(): self.assertEqual(row[:3],after[family][oldid][:3])
    if element:
     path.write_text(path.read_text().replace('<name>New available content</name>','<name>Changed content</name>'))
    else:
     document=json.loads(path.read_text());next(iter(document.values()))[-1]['name']='Changed content';path.write_text(json.dumps(document))
    self.seal(root,catalog);changed=self.run_index(root)
    self.assertNotEqual(after[family][str(newid)][1],changed[family][str(newid)][1])
 def test_npc_presentation_is_ordered_and_separate_from_target_semantics(self):
  with tempfile.TemporaryDirectory(prefix='effective-npc-presentation-') as tmp:
   root=Path(tmp)/'bundle';shutil.copytree(FIXTURE,root)
   path=root/'files/server/conf/server/defs/NpcDefs.json'
   original={'id':0,'name':'Authoritative NPC','command':'Talk-to','attack':10,**{f'sprites{i}':-1 for i in range(1,13)},'sprites1':9000,'sprites2':9001,
    'hairColour':10,'topColour':20,'bottomColour':30,'skinColour':40,'camera1':145,'camera2':200,'walkModel':4,'combatModel':4,'combatSprite':1}
   def write(row):path.write_text(json.dumps({'npcs':[row]}));self.seal(root);return self.run_index(root)['npc']['0']
   before=write(original)
   changes=[{field:original[field]+1} for field in ['hairColour','topColour','bottomColour','skinColour','camera1','camera2','walkModel','combatModel','combatSprite']]
   changes += [{'sprites1':9001,'sprites2':9000},{'sprites2':9000},{'sprites3':9000}]
   for change in changes:
    with self.subTest(presentation=change):
     after=write({**original,**change});self.assertEqual(before[1],after[1]);self.assertNotEqual(before[2],after[2])
   for field,value in [('name','Different NPC'),('command','Attack'),('attack',11)]:
    with self.subTest(semantics=field):
     after=write({**original,field:value});self.assertNotEqual(before[1],after[1]);self.assertEqual(before[2],after[2])
 def test_whitespace_and_json_order_do_not_change_effective_identity(self):
  before=self.run_index(FIXTURE)
  with tempfile.TemporaryDirectory(prefix='effective-content-format-') as tmp:
   root=Path(tmp)/'bundle';shutil.copytree(FIXTURE,root)
   path=root/'files/server/conf/server/defs/NpcDefsCustom.json';value=json.loads(path.read_text());path.write_text(json.dumps(value,separators=(',',':')))
   self.seal(root);self.assertEqual(before,self.run_index(root))
 def test_added_assets_and_definitions_leave_existing_dependency_closures_unchanged(self):
  spec=importlib.util.spec_from_file_location('bundle_test',ROOT/'tests/myworld/test-world-builder-project-content-bundle.py');bt=importlib.util.module_from_spec(spec);spec.loader.exec_module(bt)
  def sprite(frames=1):
   return bytes((0,frames,0,0x12,0x34,0x56))+b''.join(struct.pack('>HHBhhHHB',1,1,0,0,0,1,1,0) for _ in range(frames))
  def archive(entries):
   raw=bytes((len(entries),))
   for group,values in sorted(entries.items()):
    raw+=group.encode()+b'\0'+len(values).to_bytes(2,'big')
    for name,payload in sorted(values.items()):raw+=name.encode()+b'\0'+payload
   return gzip.compress(raw,mtime=0)
  def model_archive(names):
   payload=struct.pack('>HH',3,1)+struct.pack('>hhh',0,64,0)+struct.pack('>hhh',0,0,-64)+struct.pack('>hhh',0,0,64)+bytes((3,))+struct.pack('>hh',0,0)+bytes((0,0,1,2))
   raw=len(names).to_bytes(2,'big')
   for name in names:
    value=0
    for c in name.upper():value=(value*61+ord(c)-32)&0xffffffff
    raw+=struct.pack('>I',value)+len(payload).to_bytes(3,'big')*2
   raw+=payload*len(names);return len(raw).to_bytes(3,'big')*2+raw
  def animation(identity,first,name):
   return {'animationId':identity,'name':name,'category':'npc','charColour':0,'blueMask':0,'genderModel':0,'hasCombatFrames':False,'hasSpecialCombatFrames':False,'requiredFrameCount':15,'customSpriteSubspace':'npc','customSpriteEntry':name,'customEntrySha256':hashlib.sha256(name.encode()+b'\0'+sprite(15)).hexdigest(),'authenticBaseSpriteId':first,'authenticFrameSha256s':[hashlib.sha256(frames[i]).hexdigest() for i in range(first,first+15)]}
  with tempfile.TemporaryDirectory(prefix='effective-content-dependencies-') as tmp:
   root=Path(tmp)/'bundle';shutil.copytree(ROOT/'tests/fixtures/project-content-bundle-v2/bundle',root)
   defs=root/'files/server/conf/server/defs';video=root/'files/client/Cache/video'
   values={'items':{'0':sprite()},'textures':{str(i):sprite() for i in range(32)},'npc':{'fixture':sprite(15)},'player':{'head1':sprite(18)}}
   (video/'Custom_Sprites.osar').write_bytes(archive(values))
   (video/'spritepacks/Menus.osar').write_bytes(archive({'GUI':{'0':sprite()}}))
   frames={i:sprite() for i in list(range(100,130))+list(range(18))+[417,2150]}
   with zipfile.ZipFile(video/'Authentic_Sprites.orsc','w') as z:
    for i,payload in frames.items():z.writestr(str(i),payload)
   for filename in ['NpcDefs.json','NpcDefsCustom.json']:
    document=json.loads((defs/filename).read_text())
    for row in document['npcs']:row.update({f'sprites{i}':(0 if row.get('id')==0 else 2000) if i==1 else -1 for i in range(1,13)})
    (defs/filename).write_text(json.dumps(document))
   (defs/'DoorDef.xml').write_text('<DoorDef-array>'+''.join(f'<DoorDef><name>wall{i}</name><modelVar2>0</modelVar2><modelVar3>1</modelVar3></DoorDef>' for i in range(220))+'</DoorDef-array>')
   (defs/'GameObjectDef.xml').write_text('<GameObjectDef-array>'+''.join(f'<GameObjectDef><name>object{i}</name><objectModel>sample</objectModel><width>1</width><height>1</height></GameObjectDef>' for i in range(60))+'</GameObjectDef-array>')
   (video/'models.orsc').write_bytes(model_archive(['sample.ob3']))
   registry={'schemaVersion':1,'manifestType':'world-builder-npc-animation-registry','animations':[animation(2000,100,'fixture')]}
   def seal():
    path=root/'manifest.json';manifest=json.loads(path.read_text());manifest['files']=[r for r in manifest['files'] if r['role']!='metadata.npc-animations'];manifest['schemaVersion']=2;manifest['capabilityId']='project-local-custom-content-v2';path.write_text(json.dumps(manifest))
    bt.ProjectContentBundleFixtureTest.rewrite_v2_manifest(root)
    bt.ProjectContentBundleFixtureTest.promote_to_v3(root,registry)
   # Invisible floor/wall materials are renderer values, never sprite IDs.
   path=defs/'TileDef.xml';path.write_text(path.read_text().replace('<colour>0</colour>','<colour>12345678</colour>',1))
   path=defs/'DoorDef.xml';path.write_text(path.read_text().replace('<modelVar2>0</modelVar2>','<modelVar2>12345678</modelVar2>',1))
   seal();before=self.run_index(root)
   # An explicit registry row and the immutable supported fallback describe
   # identical renderer inputs; metadata provenance cannot create visual drift.
   baseline=json.loads((CLASSES/'com/openrsc/worldbuilder/authoring-lookups/animation-visuals.json').read_text())['animations'][0]
   explicit={**baseline,'genderModel':999,'customSpriteSubspace':'player','customSpriteEntry':'head1','customEntrySha256':hashlib.sha256(b'head1\0'+sprite(18)).hexdigest(),'authenticFrameSha256s':[hashlib.sha256(frames[i]).hexdigest() for i in range(18)]}
   registry['animations'].insert(0,explicit);seal();explicit_index=self.run_index(root)
   self.assertEqual(before['npc']['0'],explicit_index['npc']['0'])
   # Known captured animations must still report unresolved nested archives.
   # Ordered slots/frames cannot hide diagnostics that used to be top-level.
   for filename,role in [('Authentic_Sprites.orsc','asset.sprite.authentic')]:
    path=video/filename;original_bytes=path.read_bytes();path.write_bytes(gzip.compress(b'opaque legacy archive fixture',mtime=0));seal()
    unresolved=self.run_index(root,report=Path(tmp))
    warning=next(row for row in unresolved['warnings'] if row['family']=='npc' and row['id']==0)
    self.assertTrue(any(role in message for message in warning['messages']),warning)
    path.write_bytes(original_bytes);seal()
   explicit['charColour']=2;seal();changed_mask=self.run_index(root)
   self.assertNotEqual(before['npc']['0'][2],changed_mask['npc']['0'][2])
   registry['animations'].pop(0);seal()
   values['textures']['32']=sprite();values['items']['1']=sprite();values['npc']['second']=sprite(15);(video/'Custom_Sprites.osar').write_bytes(archive(values))
   (video/'models.orsc').write_bytes(model_archive(['sample.ob3','new-model.ob3']))
   registry['animations'].append(animation(2001,115,'second'))
   for filename,array,newrow in [('NpcDefsCustom.json','npcs',{'id':847,'name':'New NPC',**{f'sprites{i}':2001 if i==1 else -1 for i in range(1,13)}}),('ItemDefsCustom.json','items',{'id':9003,'name':'New item'})]:
    path=defs/filename;document=json.loads(path.read_text());document[array].append(newrow);path.write_text(json.dumps(document))
   for filename,element,body in [('TileDef.xml','TileDef','<colour>32</colour>'),('DoorDef.xml','DoorDef','<name>New wall</name><modelVar2>32</modelVar2><modelVar3>32</modelVar3>'),('GameObjectDef.xml','GameObjectDef','<name>New scenery</name><objectModel>new-model</objectModel><width>1</width><height>1</height>')]:
    path=defs/filename;path.write_text(path.read_text().replace(f'</{element}-array>',f'<{element}>{body}</{element}></{element}-array>'))
   path=root/'manifest.json';manifest=json.loads(path.read_text())
   for field,newid in [('tiles',32),('boundaries',220),('scenery',60),('npcs',847),('groundItems',9003)]:manifest['definitionCatalog'][field].append(newid)
   manifest['definitionCatalog']['catalogSha256']=generator.self_hash(manifest['definitionCatalog'],'catalogSha256')
   manifest['itemVisuals'].append({'itemId':9003,'authenticSpriteId':None,'customSpriteAssetRole':'asset.sprite.custom','customSpriteSubspace':'items','customSpriteEntry':'1','pictureMask':0,'blueMask':0})
   path.write_text(json.dumps(manifest))
   visual=root/'files/server/conf/world-builder/item-visuals-v1.json';doc=json.loads(visual.read_text());doc['itemVisuals']=manifest['itemVisuals'];visual.write_text(json.dumps(doc))
   seal();after=self.run_index(root)
   for family in ['floor','boundary','scenery','npc']:
    for identity,row in before[family].items():self.assertEqual(row[:3],after[family][identity][:3],(family,identity))
   for identity in ['0','9000','9001','9002']:self.assertEqual(before['ground-item'][identity][:3],after['ground-item'][identity][:3])
   # A model's unchanged bytes still depend on its referenced face textures.
   original_texture=values['textures']['0'];values['textures']['0']=original_texture[:3]+bytes((0x65,0x43,0x21))+original_texture[6:]
   (video/'Custom_Sprites.osar').write_bytes(archive(values));seal();texture_changed=self.run_index(root)
   self.assertEqual(after['scenery']['0'][1],texture_changed['scenery']['0'][1])
   self.assertNotEqual(after['scenery']['0'][2],texture_changed['scenery']['0'][2])
   self.assertNotEqual(after['boundary']['1'][2],texture_changed['boundary']['1'][2])
   self.assertEqual(after['boundary']['0'],texture_changed['boundary']['0'])
   self.assertEqual(after['npc']['0'],texture_changed['npc']['0'])
   values['textures']['0']=original_texture;(video/'Custom_Sprites.osar').write_bytes(archive(values));seal()
   # Immutable baseline item0 resolves its actual +2150 frame and items/0 entry.
   with zipfile.ZipFile(video/'Authentic_Sprites.orsc','w') as z:
    for i,payload in frames.items():z.writestr(str(i),payload+(b'baseline change' if i==2150 else b''))
   seal();baseline_changed=self.run_index(root)
   self.assertEqual(after['ground-item']['0'][1],baseline_changed['ground-item']['0'][1])
   self.assertNotEqual(after['ground-item']['0'][2],baseline_changed['ground-item']['0'][2])
   self.assertEqual(after['ground-item']['9000'],baseline_changed['ground-item']['9000'])
   # A registry hash alone cannot bless changed frame bytes.
   with zipfile.ZipFile(video/'Authentic_Sprites.orsc','w') as z:
    for i,payload in frames.items():z.writestr(str(i),payload+(b'changed' if i==100 else b''))
   seal();self.assertIn('authentic frame differs',self.run_index(root,False))
   with zipfile.ZipFile(video/'Authentic_Sprites.orsc','w') as z:
    for i,payload in frames.items():z.writestr(str(i),payload)
   # A matching payload hash cannot bless an incompatible renderer frame count.
   values['npc']['fixture']=sprite(14);(video/'Custom_Sprites.osar').write_bytes(archive(values))
   registry['animations'][0]['customEntrySha256']=hashlib.sha256(b'fixture\0'+sprite(14)).hexdigest()
   seal();self.assertIn('custom animation entry differs',self.run_index(root,False))
   # Private RGB allocation addresses and generated names do not change pixels.
   values['npc']['fixture']=sprite(15);(video/'Custom_Sprites.osar').write_bytes(archive(values))
   registry['animations'][0]['customEntrySha256']=hashlib.sha256(b'fixture\0'+sprite(15)).hexdigest()
   rgb_frames=[struct.pack('>iiBiiiiI',1,1,1,-1,2,3,4,0x123400+i) for i in range(15)]
   rgb={'animationId':3000,'name':'private-a','category':'npc','charColour':2,'blueMask':0,'genderModel':0,'hasCombatFrames':False,'hasSpecialCombatFrames':False,'requiredFrameCount':15,'frameSource':'authentic-rgb','authenticBaseSpriteId':3000,'authenticFrameSha256s':[hashlib.sha256(frame).hexdigest() for frame in rgb_frames]}
   registry['animations'].append(rgb)
   def write_rgb(identity,base):
    rgb.update(animationId=identity,authenticBaseSpriteId=base)
    doc=json.loads((defs/'NpcDefs.json').read_text());doc['npcs'][0]['sprites1']=identity;(defs/'NpcDefs.json').write_text(json.dumps(doc))
    with zipfile.ZipFile(video/'Authentic_Sprites.orsc','w') as z:
     for i,payload in frames.items():z.writestr(str(i),payload)
     for i,payload in enumerate(rgb_frames):z.writestr(str(base+i),payload)
    seal();return self.run_index(root)['npc']['0']
   rgb_before=write_rgb(3000,3000);rgb['name']='private-reallocated'
   rgb_after=write_rgb(3100,3100);self.assertEqual(rgb_before[:3],rgb_after[:3])
   rgb['blueMask']=123;seal();self.assertEqual(rgb_after[:3],self.run_index(root)['npc']['0'][:3])
   rgb.update(npcMaskPolicy='literal-and-skin',sourceAnimationId=230,sourceCustomSprites=True)
   seal();policy_changed=self.run_index(root)['npc']['0'];self.assertEqual(rgb_after[1],policy_changed[1]);self.assertNotEqual(rgb_after[2],policy_changed[2])
   for patch in [{'npcMaskPolicy':'top-and-skin'},{'sourceAnimationId':229},{'sourceCustomSprites':'true'},{'sourceAnimationId':65536}]:
    saved=dict(rgb);rgb.update(patch);seal();self.run_index(root,False);rgb.clear();rgb.update(saved)
   del rgb['sourceAnimationId'];seal();self.run_index(root,False)

 def test_unresolved_dependencies_have_named_bounded_project_diagnostics(self):
  with tempfile.TemporaryDirectory(prefix='effective-content-warning-') as temp:
   project=Path(temp);root=project/'bundle';shutil.copytree(FIXTURE,root)
   path=root/'files/server/conf/server/defs/NpcDefs.json';document=json.loads(path.read_text())
   document['npcs'][0].update({f'sprites{i}':9000 if i==1 else -1 for i in range(1,13)});path.write_text(json.dumps(document));self.seal(root)
   before={p:p.read_bytes() for p in root.rglob('*') if p.is_file()};result=self.run_index(root,report=project)
   warning=next(row for row in result['warnings'] if row['family']=='npc' and row['id']==0)
   self.assertEqual('fixture-base-npc',warning['name']);self.assertIn('NPC animation 9000',warning['messages'][0])
   report=json.loads((project/'diagnostics/content-visual-resolution-v1.json').read_text())
   self.assertEqual(result['warnings'],report['unresolved']);self.assertIn('Some may display fallback visuals',result['summary'])
   self.assertIn('content-visual-resolution-v1.json',result['summary']);self.assertLess(len(result['summary']),1800)
   self.assertEqual(before,{p:p.read_bytes() for p in before})

 def test_tampered_durable_bundle_is_rejected(self):
  with tempfile.TemporaryDirectory(prefix='effective-content-tamper-') as tmp:
   root=Path(tmp)/'bundle';shutil.copytree(FIXTURE,root)
   path=root/'files/server/conf/server/defs/NpcDefsCustom.json';path.write_bytes(path.read_bytes()+b' ')
   self.assertIn('differs from the exact manifest',self.run_index(root,False))

if __name__=='__main__':unittest.main()
