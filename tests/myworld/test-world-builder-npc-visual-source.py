#!/usr/bin/env python3
"""Static source metadata bridge; target Java is deliberately never compiled/run."""
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import struct
import subprocess
import tempfile
import unittest
import zlib

ROOT=Path(__file__).resolve().parents[2]
FIXTURE=ROOT/'tests/myworld/fixtures/npc-visual-source-format'
HARNESS=r'''
package com.openrsc.worldbuilder;
import java.nio.file.*;import java.util.*;
public final class NpcVisualSourceProbe {
 public static void main(String[] args)throws Exception {
  WorldBuilderReadOnlyTarget target=WorldBuilderReadOnlyTarget.open(Paths.get(args[0]));
  List<WorldBuilderReadOnlyTarget.FileState> evidence=new ArrayList<>();Set<String> explicit=new HashSet<>();
  for(int i=1;i<args.length;i++)explicit.add(args[i]);
  List<Map<String,Object>> rows=WorldBuilderNpcVisualSourceAdapter.discover(target,WorldBuilderPackedSourceLayout.canonical(),evidence,explicit);
  List<Object> files=new ArrayList<>();for(WorldBuilderReadOnlyTarget.FileState state:evidence){Map<String,Object> f=new LinkedHashMap<>();f.put("role",state.role);f.put("path",state.relativePath);files.add(f);}
  Map<String,Object> report=new LinkedHashMap<>();report.put("rows",rows);report.put("evidence",files);System.out.println(WorldBuilderJsonDocuments.canonical(report));
 }
}
'''

def png(path,width,height):
 def chunk(name,data):return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data))
 raw=b''.join(b'\0'+bytes((17,53,91,255))*width for _ in range(height))
 path.parent.mkdir(parents=True,exist_ok=True)
 path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))

class NpcVisualSourceTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  subprocess.run([str(ROOT/'scripts/build-tools.sh')],check=True,stdout=subprocess.DEVNULL)
  cls.compiled=tempfile.TemporaryDirectory(prefix='npc-source-probe-');cls.addClassCleanup(cls.compiled.cleanup)
  source=Path(cls.compiled.name)/'NpcVisualSourceProbe.java';source.write_text(HARNESS)
  subprocess.run(['javac','-cp',str(ROOT/'output/world-builder-tools/classes'),'-d',cls.compiled.name,str(source)],check=True)

 def fixture(self):
  temp=tempfile.TemporaryDirectory(prefix='npc-source-format-');self.addCleanup(temp.cleanup);root=Path(temp.name)
  source=root/'Client_Base/src/demo';source.mkdir(parents=True)
  for f in FIXTURE.glob('*.java'):shutil.copyfile(f,source/f.name)
  catalog=root/'server/conf/server/defs/ForeignNpcDefs.json';catalog.parent.mkdir(parents=True)
  catalog.write_text(json.dumps({'npcs':[{'id':1173,'name':'Test creature'},{'id':1174,'name':'Other creature'}]}))
  art=root/'art/paint.v4/npc.samples';entries=[]
  for id,key,widths,height in [(1173,'speckled',[32]*6,96),(1174,'curved',[24]*5,120)]:
   image=art/(key+'.png');png(image,sum(widths),height)
   entries.append({'id':id,'key':key,'columns':widths,'height':height,'sha256':hashlib.sha256(image.read_bytes()).hexdigest()})
  (art/'provenance.json').write_text(json.dumps({'entries':entries}))
  return root

 def run_probe(self,root,explicit=(),success=True):
  r=subprocess.run(['java','-cp',f'{ROOT}/output/world-builder-tools/classes:{self.compiled.name}','com.openrsc.worldbuilder.NpcVisualSourceProbe',str(root),*explicit],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  self.assertEqual(success,r.returncode==0,r.stderr)
  return json.loads(r.stdout) if success else r.stderr

 def test_source_association_cameras_reuse_and_evidence(self):
  root=self.fixture();r=self.run_probe(root);rows=r['rows'];self.assertEqual([1173,1174],[x['npcId']for x in rows])
  self.assertEqual([(76,76),(57,96)],[(x['cameraWidth'],x['cameraHeight'])for x in rows])
  self.assertEqual([18,18],[len(x['frames'])for x in rows])
  self.assertEqual(rows[1]['frames'][6:9],rows[1]['frames'][15:18])
  self.assertEqual(64,rows[0]['alphaThreshold']);self.assertEqual(1,rows[0]['spriteSlot'])
  self.assertEqual('art/paint.v4/npc.samples/speckled.png',rows[0]['frames'][0]['imagePath'])
  paths={x['path']for x in r['evidence']};self.assertIn('Client_Base/src/demo/CreatureDefinitions.java',paths)
  self.assertIn('art/paint.v4/npc.samples/provenance.json',paths)

 def test_renamed_ids_classes_paths_and_dimensions(self):
  root=self.fixture();rng=random.Random(81231);new_id=rng.randrange(2000,3000)
  replacements={'SpecimenShapes':'CatalogA91','PresentationClient':'RendererQ7','CreatureDefinitions':'DefinitionsX8','SheetImageDecoder':'DecoderT3','CREATURE':'OPAQUE_1','REUSE':'OPAQUE_2','Test creature':'Copper spirit','Other creature':'Woven entity','1173':str(new_id),'1174':str(new_id+1),'speckled':'copper.v2','curved':'woven.v3','art/paint.v4/npc.samples':'assets/q.v5/unknown.family'}
  for file in (root/'Client_Base/src/demo').glob('*.java'):
   text=file.read_text()
   for old,new in replacements.items():text=text.replace(old,new)
   text=text.replace('32,32,32,32,32,32','36,36,36,36,36,36').replace('?32:40','?35:40');file.write_text(text)
  catalog=root/'server/conf/server/defs/ForeignNpcDefs.json';catalog.write_text(json.dumps({'npcs':[{'id':new_id,'name':'Copper spirit'},{'id':new_id+1,'name':'Woven entity'}]}))
  old=root/'art/paint.v4/npc.samples';new=root/'assets/q.v5/unknown.family';new.mkdir(parents=True)
  entries=json.loads((old/'provenance.json').read_text())['entries']
  for entry,key,widths,height in zip(entries,['copper.v2','woven.v3'],[[36]*6,[24]*5],[105,120]):
   entry.update(id=new_id if key=='copper.v2' else new_id+1,key=key,columns=widths,height=height)
   image=new/(key+'.png');png(image,sum(widths),height);entry['sha256']=hashlib.sha256(image.read_bytes()).hexdigest()
  (new/'provenance.json').write_text(json.dumps({'entries':entries}));shutil.rmtree(old)
  rows=self.run_probe(root)['rows'];self.assertEqual(new_id,rows[0]['npcId']);self.assertEqual((86,84),(rows[0]['cameraWidth'],rows[0]['cameraHeight']));self.assertEqual(36,rows[0]['frames'][0]['width']);self.assertEqual(35,rows[0]['frames'][0]['height'])

 def test_modified_semantics_refused_with_explicit_descriptor_escape(self):
  variants=[('SheetImageDecoder.java','framesPerDirection, -1','framesPerDirection, 123'),('SheetImageDecoder.java','normalizePixels(frame.getPixels(), 64)','normalizePixels(frame.getPixels(), variableAlpha)'),('SheetImageDecoder.java','frameIndex * frameHeight','frameIndex + frameHeight'),('SpecimenShapes.java','this==CREATURE?32:40','this==TYPO?32:40'),('CreatureDefinitions.java','npc.sprites[0]','npc.sprites[unknown]'),('CreatureDefinitions.java','Integer id=registered.get(preview)','Integer id=otherRegistry.get(preview)'),('PresentationClient.java','entry = preview.withCombatFrames(entry);','entry = unknown(entry);')]
  for filename,before,after in variants:
   with self.subTest(filename=filename,mutation=after):
    root=self.fixture();file=root/'Client_Base/src/demo'/filename;text=file.read_text();self.assertIn(before,text);file.write_text(text.replace(before,after));self.run_probe(root,success=False)
    report=self.run_probe(root,explicit=['server/conf/server/defs/ForeignNpcDefs.json#0','server/conf/server/defs/ForeignNpcDefs.json#1']);self.assertEqual([],report['rows'])

 def test_unrelated_java_literals_and_long_strings_do_not_block(self):
  root=self.fixture();(root/'Client_Base/src/demo/Unrelated.java').write_text("class Unrelated {char a='{', b=')', c='\\'';String huge=\""+'x'*100000+'"; String block="""\n { arbitrary ) text }\n"""; }\nenum Other{ X(\"id\", 1, 2); Other(String value,int...xs){this.value=value;} }')
  self.assertEqual(2,len(self.run_probe(root)['rows']))

 def test_provenance_hash_drift_and_symlink_refused(self):
  root=self.fixture();image=root/'art/paint.v4/npc.samples/speckled.png';image.write_bytes(image.read_bytes()+b'drift');self.run_probe(root,success=False)
  root=self.fixture();image=root/'art/paint.v4/npc.samples/speckled.png';image.rename(image.with_suffix('.original'));image.symlink_to(image.with_suffix('.original').name);self.run_probe(root,success=False)

 def test_sequential_base_ids_and_active_patch_selection(self):
  root=self.fixture();definitions=root/'server/conf/server/defs';catalog=definitions/'ForeignNpcDefs.json';catalog.unlink()
  (definitions/'NpcDefs.json').write_text(json.dumps({'npcs':[{'name':'Test creature'},{'name':'Other creature'}]}))
  (definitions/'NpcDefsCustom.json').write_text('{"npcs":[]}')
  table=root/'Client_Base/src/demo/SpecimenShapes.java';table.write_text(table.read_text().replace('1173','0').replace('1174','1'))
  proof=root/'art/paint.v4/npc.samples/provenance.json';doc=json.loads(proof.read_text())
  for index,row in enumerate(doc['entries']):row['id']=index
  proof.write_text(json.dumps(doc))
  (root/'server/myworld.conf').write_text('based_config_data: 17\nwant_myworld: false\n')
  (definitions/'NpcDefsPatch18.json').write_text('invalid but inactive')
  (definitions/'NpcDefsMyWorld.json').write_text('invalid but inactive')
  rows=self.run_probe(root)['rows'];self.assertEqual([0,1],[r['npcId']for r in rows]);self.assertTrue(all(r['definitionPath'].endswith('/NpcDefs.json')for r in rows))
  (definitions/'NpcDefsPatch17.json').write_text(json.dumps({'npcs':[{'id':0,'name':'Test creature'}]}))
  rows=self.run_probe(root)['rows'];selected=next(r for r in rows if r['npcId']==0);self.assertTrue(selected['definitionPath'].endswith('/NpcDefsPatch17.json'))

 def test_optional_full_tracked_reference(self):
  raw=os.environ.get('WORLD_BUILDER_NPC_SOURCE_FIXTURE')
  if not raw:self.skipTest('Set tracked reference fixture path for owner-specific acceptance')
  report=self.run_probe(Path(raw));self.assertEqual(8,len(report['rows']));self.assertTrue(all(len(x['frames'])==18 for x in report['rows']))

if __name__=='__main__':unittest.main()
