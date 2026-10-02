#!/usr/bin/env python3
"""Content identity is independent of map population and source-file expansion."""
import importlib.util
import json
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
 def run_index(self, root, success=True):
  result=subprocess.run(['java','-cp',os.pathsep.join([self.tmp.name,str(CLASSES)]),'com.openrsc.worldbuilder.EffectiveContentHarness',str(root)],text=True,capture_output=True)
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
 def test_whitespace_and_json_order_do_not_change_effective_identity(self):
  before=self.run_index(FIXTURE)
  with tempfile.TemporaryDirectory(prefix='effective-content-format-') as tmp:
   root=Path(tmp)/'bundle';shutil.copytree(FIXTURE,root)
   path=root/'files/server/conf/server/defs/NpcDefsCustom.json';value=json.loads(path.read_text());path.write_text(json.dumps(value,separators=(',',':')))
   self.seal(root);self.assertEqual(before,self.run_index(root))
 def test_tampered_durable_bundle_is_rejected(self):
  with tempfile.TemporaryDirectory(prefix='effective-content-tamper-') as tmp:
   root=Path(tmp)/'bundle';shutil.copytree(FIXTURE,root)
   path=root/'files/server/conf/server/defs/NpcDefsCustom.json';path.write_bytes(path.read_bytes()+b' ')
   self.assertIn('differs from the exact manifest',self.run_index(root,False))

if __name__=='__main__':unittest.main()
